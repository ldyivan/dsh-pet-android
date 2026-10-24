package com.dsh.pet

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * 后台保活。
 *
 * 之前的问题：PetService 只做了「前台服务 + START_STICKY」两件事，
 * 而这两条在国产 ROM（MIUI / ColorOS / MagicOS / HyperOS / OriginOS）上基本等于没做——
 * 用户从最近任务划掉应用时，ROM 会把整个进程连前台服务一起杀掉，桌宠直接消失，
 * 且 START_STICKY 只是「系统愿意的话帮你重启」，被用户划掉属于「用户意图」，系统不会重启。
 *
 * 这里补三层，按「越靠后越兜底」排列：
 *  1. **onTaskRemoved 立刻自启**：多数 ROM 上划掉任务时进程还没被回收，这一枪就能把自己拉回来；
 *  2. **一次性兜底闹钟**：进程如果真被杀了，AlarmManager 在几百毫秒后发一条广播把服务拉起来
 *     （广播投递会短暂把应用放进临时白名单，Android 12+ 也能启动前台服务）；
 *  3. **15 分钟看门狗**：长期被清理（清理大师、系统省电）时，最多 15 分钟内必回。
 *
 * 另加两个系统级开关（设置页可点）：电池优化白名单、自启动管理。
 * 不做「双进程互相拉起 / 1 像素 Activity」这类黑产手段——费电、且与开源项目的定位不符。
 */
object KeepAlive {

    private const val TAG = "dsh-pet"
    private const val PREF = "keep-alive"
    private const val KEY_WANTED = "pet_wanted"

    /** PendingIntent 请求码（不能与通知动作的 0/1 冲突） */
    private const val REQ_RESTART = 7341
    private const val REQ_WATCHDOG = 7342

    /** 划掉后台后多久尝试自启：太短会和系统回收撞车，太长用户会觉得「半天才回来」 */
    private const val RESTART_DELAY_MS = 800L

    /** 看门狗周期：15 分钟是 Android 对后台闹钟的常见下限，再密就会被系统抹平 */
    private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 用户意图

    /**
     * 用户是否希望桌宠在跑。
     *
     * 这个标记必须**跨进程死亡保留**：进程被 ROM 杀掉时我们没有机会保存任何东西，
     * 只能靠之前落盘的意图判断「该不该自己回来」。用户在设置页点「停止桌宠」时置 false，
     * 此后任何保活逻辑都不许再拉起服务——否则就变成「关不掉的流氓软件」。
     */
    fun isWanted(ctx: Context): Boolean = try {
        prefs(ctx).getBoolean(KEY_WANTED, false)
    } catch (e: Exception) {
        false
    }

    fun setWanted(ctx: Context, wanted: Boolean) {
        try {
            prefs(ctx).edit().putBoolean(KEY_WANTED, wanted).apply()
        } catch (e: Exception) {
            Log.w(TAG, "保存保活意图失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------ 拉起服务

    /**
     * 按用户意图启动桌宠服务。
     *
     * Android 12+ 从后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，
     * 本应用已持有 SYSTEM_ALERT_WINDOW（悬浮窗）权限，属于官方豁免情形，正常情况下不会被拦；
     * 仍然 try/catch——被拦时返回 false，由调用方决定是否再挂闹钟兜底。
     */
    fun startPet(ctx: Context, why: String): Boolean {
        if (!isWanted(ctx)) return false
        return try {
            val i = Intent(ctx, PetService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            Log.i(TAG, "保活拉起服务($why)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "保活拉起服务失败($why): ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------ 闹钟

    private fun restartPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQ_RESTART,
        Intent(ctx, KeepAliveReceiver::class.java).setAction(KeepAliveReceiver.ACTION_RESTART),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun watchdogPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQ_WATCHDOG,
        Intent(ctx, KeepAliveReceiver::class.java).setAction(KeepAliveReceiver.ACTION_WATCHDOG),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 能不能用精确闹钟（Android 12+ 需要 SCHEDULE_EXACT_ALARM，本应用没申请，走降级分支） */
    private fun canExact(ctx: Context): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(ctx).canScheduleExactAlarms()
    } catch (e: Exception) {
        false
    }

    /** 划掉后台后的兜底：一次性闹钟，几百毫秒后把自己叫回来 */
    fun scheduleRestart(ctx: Context) {
        if (!isWanted(ctx)) return
        val at = System.currentTimeMillis() + RESTART_DELAY_MS
        try {
            if (canExact(ctx)) {
                am(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, restartPi(ctx))
            } else {
                am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, restartPi(ctx))
            }
            Log.i(TAG, "已挂重启闹钟 +${RESTART_DELAY_MS}ms")
        } catch (e: Exception) {
            Log.w(TAG, "挂重启闹钟失败: ${e.message}")
        }
    }

    /**
     * 15 分钟看门狗：服务在跑时它什么都不做（只刷一次通知），
     * 服务没了就把它拉起来。重复闹钟必须是「不精确」的，精确重复在 API 19+ 上会被抹平。
     */
    fun scheduleWatchdog(ctx: Context) {
        if (!isWanted(ctx)) return
        try {
            am(ctx).setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                WATCHDOG_INTERVAL_MS,
                watchdogPi(ctx),
            )
        } catch (e: Exception) {
            Log.w(TAG, "挂看门狗失败: ${e.message}")
        }
    }

    /** 用户主动停止时清掉所有保活手段，否则会「关不掉」 */
    fun cancelAll(ctx: Context) {
        try {
            am(ctx).cancel(restartPi(ctx))
            am(ctx).cancel(watchdogPi(ctx))
        } catch (e: Exception) {
            Log.w(TAG, "取消闹钟失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------ 系统级开关

    /** 是否已在电池优化白名单里（低版本没有这个机制，按已允许处理） */
    fun isIgnoringBattery(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            true
        } else {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)
        }
    } catch (e: Exception) {
        true
    }

    /**
     * 请求加入电池优化白名单。
     *
     * 已允许时跳系统列表页（允许用户反悔撤销），未允许时直接弹系统的「允许后台运行」对话框
     * ——比让用户自己去「设置 → 电池 → 优化」里翻三层要省事得多。
     */
    fun requestIgnoreBattery(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(activity, "当前系统版本无需设置", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isIgnoringBattery(activity)) {
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${activity.packageName}"),
                    )
                )
                return
            } catch (e: Exception) {
                Log.w(TAG, "请求电池优化白名单失败: ${e.message}")
            }
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(activity, "无法打开电池优化设置，请到系统设置手动允许", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 自启动 / 后台管理页。
     *
     * 各家 ROM 的自启动开关藏在不同包名的 Activity 里，没有统一 API；逐个试，全失败就退应用详情页
     * （至少能让用户看到「自启动」入口在系统里的位置）。跳不过去不算错误，属于正常降级。
     */
    fun openAutoStart(activity: Activity) {
        val candidates = listOf(
            // MIUI / HyperOS
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // ColorOS / realme UI
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            // vivo / iQOO
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            // Huawei / Honor
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            // 联想 / 中兴等轻量定制
            "com.lenovo.security" to "com.lenovo.security.purebackground.PureBackgroundActivity",
        )
        for ((pkg, cls) in candidates) {
            try {
                activity.startActivity(
                    Intent().apply {
                        setClassName(pkg, cls)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                return
            } catch (e: Exception) {
                // 这个 ROM 没有这个页面，试下一个
            }
        }
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            )
            Toast.makeText(activity, "本机型无统一自启动页，请在应用详情里找「自启动 / 后台运行」", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "无法打开系统设置，请手动在系统设置里允许自启动", Toast.LENGTH_LONG).show()
        }
    }
}
