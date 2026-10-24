package com.dsh.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 保活广播接收器：闹钟（重启 / 看门狗）+ 开机 + 应用升级后自启。
 *
 * 为什么走广播而不是直接 PendingIntent.getForegroundService：
 *  · 广播投递会把应用临时放进白名单，Android 12+ 从后台启动前台服务才不会被拦；
 *  · 开机 / 覆盖安装这类系统广播本来就只能由接收器处理，逻辑收在一处更好维护。
 *
 * 所有分支都先看 KeepAlive.isWanted：用户点了「停止桌宠」之后，任何广播都不许再把服务拉起来。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_RESTART, ACTION_WATCHDOG,
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                if (!KeepAlive.isWanted(context)) {
                    // 用户已经主动停止：顺手把残留闹钟清掉，避免它们空转
                    KeepAlive.cancelAll(context)
                    return
                }
                // 服务还活着时再 startForegroundService 只是重走一遍 onStartCommand（幂等）；
                // 进程被杀时这一枪才是真正的复活。
                val ok = KeepAlive.startPet(context, action)
                if (!ok) KeepAlive.scheduleRestart(context)
                KeepAlive.scheduleWatchdog(context)
                Log.i(TAG, "保活广播 $action → ${if (ok) "已拉起" else "拉起失败，已挂重试"}")
            }
        }
    }

    companion object {
        private const val TAG = "dsh-pet"

        /** 划掉后台后的兜底闹钟 */
        const val ACTION_RESTART = "com.dsh.pet.action.KEEPALIVE_RESTART"

        /** 15 分钟看门狗 */
        const val ACTION_WATCHDOG = "com.dsh.pet.action.KEEPALIVE_WATCHDOG"
    }
}