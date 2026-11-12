package com.dsh.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.widget.RemoteViews
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject

/**
 * 悬浮窗承载服务（桌宠常驻）。
 *
 * 双窗口架构（与 android/host.ts 的 syncTouchLayer 契约一致）：
 *  · 渲染窗口：全屏 WebView，**不可触摸、不可聚焦**——只负责把宠物画在屏幕上，
 *    WebView 视口 = 整块屏幕，于是 host 的归一化坐标（window.innerWidth/Height）语义不变。
 *  · 触摸层窗口：一个尺寸**恰好等于命中区**的透明 View（位置/尺寸由 JS 每帧 syncTouchLayer
 *    以「视口归一化比例」上报），接收真实触摸后按窗口偏移转屏幕坐标，dispatchTouchEvent
 *    注入渲染 WebView —— 等价于原项目在网页里直接按宠物命中区处理 pointer，
 *    又不会挡住命中区之外的桌面点击。
 *
 * 两个曾经踩过的坑（改这里之前先读）：
 *  1) 坐标口径必须归一化。JS 侧只有 CSS px（受 devicePixelRatio / viewport 缩放影响），
 *     直接当设备 px 用会让触摸层落在宠物旁边 → 点不中宠物。
 *  2) 触摸层**绝不能**比命中区大。它吃掉的事件不会再传给下层应用，多出来的每一寸都是
 *     「屏幕上点不动的死区」；宠物尺寸在手机上也必须按视口收敛（见 host.ts adaptPetsForScreen），
 *     否则整块屏都是死区。
 *
 * 渲染窗口保持 FLAG_NOT_FOCUSABLE：桌宠没有文本输入，抢焦点只会把下层应用的输入法/焦点弄丢。
 */
class PetService : Service() {

    /**
     * 窗口管理器。
     *
     * **完全体模式下必须从无障碍服务取**：TYPE_ACCESSIBILITY_OVERLAY 的窗口要求添加时的
     * Context 来自 AccessibilityService，用本服务自己的 Context 会被系统拒绝
     * （见 PetA11yService 的注释）。
     */
    private val wm: WindowManager
        get() = windowHost().getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 添加窗口用的 Context：完全体模式用无障碍服务，否则用自己 */
    private fun windowHost(): Context {
        val a11y = PetA11yService.instance
        return if (trustedOverlayWanted() && a11y != null) a11y else this
    }

    /** 设置页里的「完全体模式」开关（ui-settings.json 的 trustedOverlay） */
    private fun trustedOverlayRequested(): Boolean = try {
        JSONObject(Store.readUiSettings(this)).optBoolean("trustedOverlay", false)
    } catch (e: Exception) {
        false
    }

    /**
     * 完全体模式是否真的生效：开关打开 + 无障碍已连接 + 上次添加窗口没被系统拒绝。
     *
     * 三个条件缺一不可——开关打开但没授权无障碍时仍是普通悬浮窗（否则 addView 直接抛异常，
     * 桌宠就整只消失了），被 ROM 拒绝过一次也不再重试。
     */
    private fun trustedOverlayWanted(): Boolean =
        trustedOverlayRequested() && PetA11yService.isEnabled() && !trustedOverlayDenied

    /** 机型 / ROM 不允许无障碍覆盖层时置位，本次运行内不再重试（避免反复失败刷日志） */
    @Volatile
    private var trustedOverlayDenied = false
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    /**
     * 触摸层窗口池：**每个命中元素一个窗口**（见 applyTouchLayerRects 注释）。
     *
     * 之前是「一个窗口 = 所有命中元素的并集」，实测会被并集撑成横跨整屏的一条大带子
     * （宠物在下方 + 气泡/菜单在上方 → 并集几乎等于全屏），触摸层把桌面的事件全吃掉，
     * 表现就是「退到桌面只能操作桌宠」。多窗口后任何一块都只有一个元素的真实大小，
     * 结构上不可能再出现大死区。
     */
    private val touchViews = ArrayList<View>()
    private val touchParamsList = ArrayList<WindowManager.LayoutParams>()
    private lateinit var renderParams: WindowManager.LayoutParams
    // 触摸层当前覆盖的最大一块（屏幕 px）——只用于日志与看门狗面积判断
    private var rectX = 0
    private var rectY = 0
    private var rectW = 1
    private var rectH = 1
    private var touchLogCount = 0
    private var downLogCount = 0

    /** 最近一次收到 JS 命中区上报的时刻（uptimeMillis）——看门狗据此判断渲染层是否还在跑 */
    @Volatile
    private var lastTouchReportAt = 0L
    /** 看门狗只启一次（onStartCommand 可能被反复叫） */
    private var watchdogStarted = false
    /** 局部 wakelock：灭屏/被划后台后仍能让重启闹钟唤醒 CPU 执行（短持 2s，不长时间占电） */
    private var wakeLock: PowerManager.WakeLock? = null
    /** 每分钟重挂保活闹钟（ROM 会清理闹钟，服务活着时周期性续命） */
    private var reArmRunnable: Runnable? = null

    /** 桌宠是否被用户「暂时隐藏」（渲染窗口 GONE + 触摸层收回，服务仍常驻） */
    @Volatile
    private var petHidden = false
    /** 长按菜单是否打开（JS 经 DshNative.setMenuOpen 上报）。
     *  打开期间触摸层收到「菜单命中区之外」的触摸时，不转发原始事件给 WebView
     *  （避免误触宠物），而是换算成 WebView 内坐标注入合成 mousedown，
     *  触发 JS 侧 document mousedown 处理器：先收子列、再关整树（需求①）。 */
    @Volatile
    private var menuOpen = false
    /** 菜单根元素命中区（屏幕 px，由 applyTouchLayersJson 中的 dsh-pet-menu 命中块记录）。
     *  外部点击判定：触摸点不在这个矩形内即为「菜单外」。 */
    @Volatile
    private var menuRect = IntArray(4) // x, y, w, h
    private var menuRectReady = false // 首次读到菜单矩形前，点按一律按「菜单内」转发，避免误判外点触发逐级收起
/** 菜单捕获层窗口（独立窗口，不占触摸窗口池；菜单打开时铺全屏拦截外部触摸）。 */
    private var captureView: View? = null
    private var captureParams: WindowManager.LayoutParams? = null
    /** 捕获层当前是否已消费本次触摸（避免 UP/CANCEL 重复转发） */
    @Volatile
    private var captureConsume = false

    /** 提醒气泡专用窗口（原生常驻，独立于 dsh-pet.js 的 8 窗口池与 WebView）：
 *  FrameLayout 承载气泡卡；气泡可见时铺到屏幕固定位置，整卡可点跳设置页提醒 tab，
 *  点关闭按钮收回 1×1。不依赖 WebView 的 alpha/触摸/定时器，灭屏·后台·pauseTimers 下照样常驻可见。 */
    private var reminderView: FrameLayout? = null
    private var reminderParams: WindowManager.LayoutParams? = null
    /** 当前气泡卡内容（动态生成，整卡替换）；null = 无活跃气泡。 */
    private var reminderCard: View? = null

    /** 穿透模式：true = 触摸层全部收回 1×1，屏幕整块穿透给下层 App（宠物仍渲染但不可交互）。 */
    @Volatile
    private var clickThrough = false

    /** 穿透模式开关：true = 所有触摸层收回 1×1（屏幕整块穿透给下层 App）；false = 恢复正常跟随。
     *  退出靠通知栏「穿透/停止穿透」按钮（toggleClickThrough），不再铺屏幕角标。 */
    fun setClickThrough(on: Boolean) {
        clickThrough = on
        main.post {
            if (on) {
                for (i in touchViews.indices) {
                    val p = touchParamsList[i]
                    p.x = 0; p.y = 0; p.width = 1; p.height = 1
                    try { wm.updateViewLayout(touchViews[i], p) } catch (_: Exception) {}
                }
                Log.i("dsh-pet", "穿透模式已开启（触摸层收回，屏幕穿透）")
            } else {
                Log.i("dsh-pet", "穿透模式已关闭（恢复正常触摸）")
            }
        }
    }

    /** 穿透模式 toggle（通知栏按钮调用）：翻转状态 + 刷新通知栏按钮标签。 */
    fun toggleClickThrough() {
        setClickThrough(!clickThrough)
        updateForegroundNotification()
    }

    /** 吃白饭玩法是否在跑（JS 经 DshNative.setBaifanRunning 上报，通知栏「吃白饭/停止吃白饭」按钮标签据此动态切换）。 */
    @Volatile
    private var baifanRunning = false

    /** 吃白饭运行状态上报（JS 开演/收场边界调用）：更新字段 + 刷新通知栏按钮标签。幂等（同状态不重刷）。 */
    fun setBaifanRunning(on: Boolean) {
        if (baifanRunning == on) return
        baifanRunning = on
        Log.i("dsh-pet", "吃白饭状态上报: " + if (on) "开（通知按钮→停止吃白饭）" else "停（通知按钮→吃白饭）")
        updateForegroundNotification()
    }

    /**
     * 屏幕开关/解锁广播：部分 ROM 在灭屏、亮屏、解锁时会清理应用闹钟，
     * 导致看门狗/重启闹钟被抹掉、桌宠再也回不来。这里收到广播就重挂一遍。
     * （只重挂不拉起：服务还活着时重挂就是续命；进程死了自然由闹钟拉起。）
     */
    private val screenKeepAliveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val a = intent.action ?: return
            if (a == Intent.ACTION_SCREEN_ON || a == Intent.ACTION_SCREEN_OFF || a == Intent.ACTION_USER_PRESENT) {
                if (KeepAlive.isWanted(this@PetService)) {
                    KeepAlive.scheduleWatchdog(this@PetService)
                    Log.i("dsh-pet", "屏幕广播 $a → 已重挂看门狗")
                }
                // 吃白饭玩法省电：灭屏自动暂停、亮屏（解锁）自动恢复。
                // JS 侧 __dshBaifanPause/Resume 幂等（未在跑/非自动暂停态时是 no-op），
                // 直接对 WebView 调，服务被杀时 WebView 为 null 自动跳过。
                main.post {
                    when (a) {
                        Intent.ACTION_SCREEN_OFF -> {
                            // 灭屏：暂停吃白饭 + 冻结 WebView 定时器（省电）
                            webView?.evaluateJavascript("window.__dshBaifanPause && window.__dshBaifanPause()", null)
                            try { webView?.onPause() } catch (_: Exception) {}
                            try { webView?.pauseTimers() } catch (_: Exception) {}
                        }
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                            // 亮屏/解锁：恢复被灭屏冻结的 JS 渲染循环（移动/漫游等动作靠它）
                            // 不 resume 桌宠动作会一直停住（v1.3.2 移动失效根因）
                            try { webView?.resumeTimers() } catch (_: Exception) {}
                            try { webView?.onResume() } catch (_: Exception) {}
                            webView?.evaluateJavascript("window.__dshBaifanResume && window.__dshBaifanResume()", null)
                        }
                    }
                }
            }
        }
    }

    /** 点击音效合成器（懒建，销毁时释放；ToneGenerator 反复 new 会泄漏音频资源） */
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // wm 改成按需取（见 windowHost）：完全体模式下要用无障碍服务的 Context
        // 屏幕开关广播：注册后收灭屏/亮屏/解锁事件，重挂保活闹钟（见 screenKeepAliveReceiver）
        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenKeepAliveReceiver, f)
            Log.i("dsh-pet", "屏幕保活广播已注册")
        } catch (e: Exception) {
            Log.w("dsh-pet", "屏幕保活广播注册失败: ${e.message}")
        }
        // 保活强化①：短持 PARTIAL_WAKE_LOCK（2 秒），保证灭屏/被划后台后 800ms 重启闹钟能唤醒 CPU 执行，不长时间占电
        val wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh-pet:restart-alarm")
        wl.acquire(2000L)
        wakeLock = wl
        // 保活强化②：每分钟重挂保活闹钟。ROM 常清理一次性/重复闹钟，服务活着时周期性续命
        reArmRunnable = object : Runnable {
            override fun run() {
                if (KeepAlive.isWanted(this@PetService)) KeepAlive.scheduleWatchdog(this@PetService)
                main.postDelayed(this, 60_000L)
            }
        }
        main.postDelayed(reArmRunnable!!, 60_000L)
        Log.i("dsh-pet", "保活强化：wakelock + 每分钟重挂闹钟循环已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏按钮走 startService + action，窗口已存在时也要先处理 action 再返回
        val action = intent?.action
        if (webView == null) {
            startForegroundSafe()
            createRenderWindow()
            createTouchWindow()
            loadRenderer()
            applyWindowOpacity()
            startTouchWatchdog()
        }
        when (action) {
            ACTION_HIDE_PET -> setPetHidden(true)
            ACTION_SHOW_PET -> setPetHidden(false)
            ACTION_PET_HOME -> {
                // 归位：与右键菜单「回到初始位置」完全一致（停漫游/移动/抛掷、清拖拽位置、回配置角落）
                main.post {
                    webView?.evaluateJavascript("window.__dshPetHome && window.__dshPetHome()", null)
                }
                updateForegroundNotification()
            }
            ACTION_PET_SIZE_INC -> adjustPetSize(+0.05f)
            ACTION_PET_SIZE_DEC -> adjustPetSize(-0.05f)
            ACTION_PET_SIZE_SET -> {
                val ratio = intent?.getDoubleExtra(EXTRA_PET_SIZE_RATIO, -1.0) ?: -1.0
                if (ratio > 0) setPetSizeRatio(ratio)
            }
            ACTION_BAIFAN -> startBaifan()
            ACTION_BAIFAN_STOP -> stopBaifan()
            ACTION_CLICK_THROUGH -> toggleClickThrough()
            ACTION_REMINDER_FIRE -> {
                val ids = intent?.getStringArrayExtra(EXTRA_REMINDER_IDS)?.toList() ?: emptyList()
                fireReminders(ids)
            }
        }
        // 走到这里说明本次启动是「用户希望桌宠运行」的（启动按钮 / 通知栏动作 / 保活拉起）：
        // 落盘意图（进程被 ROM 杀掉后靠它决定该不该复活），并挂上 15 分钟看门狗。
        // 用户主动停止时由 SettingsActivity.stopPet 清掉这两样，所以不会变成关不掉的进程。
        KeepAlive.setWanted(this, true)
        KeepAlive.scheduleWatchdog(this)
        // 保活强化：每次被叫起（用户/闹钟/广播）时续一下短 wakelock，保证紧接着的闹钟/广播能唤醒 CPU
        wakeLock?.let { if (!it.isHeld) it.acquire(2000L) }
        // 通知栏自愈：任何一次被叫起（设置页启动 / 点任意通知按钮 / 保活拉起）都强制用当前进程代码
        // 重建常驻通知。覆盖安装不会杀掉正在跑的前台服务进程，旧进程的 ongoing 通知会一直挂着不刷新
        // （v1.2.17 通知栏按钮不更新的根因）；这条让点任意旧通知按钮 → startService → 这里重刷，
        // 一旦运行的是新代码进程，通知即刻变成最新布局，无需手动停服务。
        updateForegroundNotification()
        return START_STICKY
    }

    /**
     * 最近任务被划掉（用户从多任务里 swipe 掉本应用）。
     *
     * 这是「桌宠被关掉」的主因：很多 ROM 把划掉任务当成强杀信号，连前台服务一起干掉。
     * START_STICKY 在这里没用——系统认为那是用户意图，不会主动重启。
     * 所以这里主动补两枪：
     *  1. 立刻再 startForegroundService 一次（此时进程往往还没被回收，能当场续命）；
     *  2. 无条件挂一个 800ms 后的闹钟——进程真被杀了，靠这条广播把服务拉回来。
     * 注意 onTaskRemoved 之后任务列表里已经没有本应用，服务会以「无任务」形态继续跑。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!KeepAlive.isWanted(this)) return
        val ok = KeepAlive.startPet(this, "task-removed")
        KeepAlive.scheduleRestart(this)
        Log.i("dsh-pet", "任务被移除，自启${if (ok) "已请求" else "请求失败，已挂闹钟"}")
    }

    // ---------------------------------------------------------------- 窗口

    private fun createRenderWindow() {
        val wv = WebView(windowHost())
        // 透明背景：WebView 里只有宠物与气泡，其余透出桌面
        wv.setBackgroundColor(0x00000000)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // 素材走 https asset 域，不涉及跨域 file 访问
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            // 后台/隐藏时由 pauseTimers 冻结 JS 与渲染，这里同时让系统在适当时机降级
            javaScriptCanOpenWindowsAutomatically = false
        }
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER

        val assetLoader = buildAssetLoader()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
            override fun onPageFinished(view: WebView?, url: String?) {
                // 页面加载完 = reminder.js 已挂 window.__dshReminderBubble；
                // 补发到点时页面尚未就绪而被暂存的提醒气泡（v1.3.0 bug 修复）
                main.post { onPageFinishedReminderFlush() }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i("dsh-pet", m.message() + " @" + m.lineNumber())
                return true
            }
        }
        wv.addJavascriptInterface(NativeBridge(this), "DshNative")

        renderParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 必须一开始就压在阈值之下：addView 到 applyWindowOpacity 之间哪怕只有一帧 alpha=1.0，
            // 那一帧里落到桌面上的触摸就会被系统判成不可信而丢弃（见 obscuringAlphaLimit 注释）。
            alpha = obscuringAlphaLimit()
        }

        try {
            wm.addView(wv, renderParams)
        } catch (e: Exception) {
            // 机型 / ROM 不允许无障碍覆盖层（个别 ROM 校验更严）：退回普通悬浮窗，
            // 透明度自动回到系统上限，其余功能不受影响。
            Log.w("dsh-pet", "无障碍覆盖层添加失败，回退普通悬浮窗: ${e.message}")
            trustedOverlayDenied = true
            renderParams.type = legacyOverlayType()
            renderParams.alpha = obscuringAlphaLimit()
            wm.addView(wv, renderParams)
        }
        webView = wv
        // 启动即打点：真机排查「桌面点不动」时，先确认这一行 alpha 是否已 ≤ 遮挡上限。
        Log.i("dsh-pet", "渲染窗口已创建，alpha = ${renderParams.alpha}（遮挡上限 ${obscuringAlphaLimit()}）")
    }

    /**
     * 触摸层窗口池：预建 [MAX_TOUCH_LAYERS] 个 1×1 透明窗口（不挡任何东西），
     * 由 JS 上报的命中区按需铺开/收回。预建而不是动态增删，是为了避免每帧
     * addView/removeView（WindowManager 重排 + 权限校验，滑动时会掉帧）。
     */
    private fun createTouchWindow() {
        for (i in 0 until MAX_TOUCH_LAYERS) addTouchLayer()
        addCaptureLayer()
        addReminderLayer()
    }

    /** 提醒气泡专用触摸窗口：初始 1×1 @(0,0) 不挡任何东西，气泡可见时由 applyReminderBubbleRect 铺开。 */
    private fun addReminderLayer() {
        val v = FrameLayout(this)
        v.setBackgroundColor(0x00000000)
        val p = WindowManager.LayoutParams(
            1, 1,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, p)
            reminderView = v
            reminderParams = p
        } catch (e: Exception) {
            Log.w("dsh-pet", "添加提醒气泡窗口失败: ${e.message}")
            reminderView = null
            reminderParams = null
        }
    }

    /** 生成一条原生常驻提醒气泡卡（白卡圆角 + 标题/正文/提示 + 右上「×」）。整卡可点跳提醒列表，点「×」关闭。 */
    private fun buildReminderCard(id: String, title: String, text: String, isPlan: Boolean): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val card = FrameLayout(this)
        val cardBg = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@PetService, R.color.dsh_bg_card))
            cornerRadius = 16f * d
            setStroke(dp(1), ContextCompat.getColor(this@PetService, R.color.dsh_accent_stroke))
        }
        card.background = cardBg
        card.elevation = dp(10).toFloat()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        content.addView(TextView(this).apply {
            this.text = (if (isPlan) "计划 · " else "备忘 · ") + title
            textSize = 14.5f
            setTextColor(ContextCompat.getColor(this@PetService,
                if (isPlan) R.color.dsh_accent else R.color.dsh_text_primary))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (text.isNotEmpty()) {
            content.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@PetService, R.color.dsh_text_hint))
                setPadding(0, dp(3), 0, 0)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        content.addView(TextView(this).apply {
            this.text = if (isPlan) "点我看步骤并逐项勾选" else "点我查看提醒列表"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@PetService, R.color.dsh_text_faint))
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val close = TextView(this).apply {
            this.text = "✕"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@PetService, R.color.dsh_text_hint))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@PetService, R.color.dsh_accent_soft))
                cornerRadius = 99f * d
            }
            setOnClickListener { hideReminderBubble() }
        }
        card.addView(close, FrameLayout.LayoutParams(dp(24), dp(24)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(4); marginEnd = dp(4)
        })
        // 整卡可点：跳设置页提醒 tab 并定位该条
        card.setOnClickListener { openRemindersPage(id) }
        card.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        return card
    }

    /** 弹原生常驻气泡：铺窗口到屏幕右上固定区，整卡可点，点「×」或跳列表后关闭。 */
    private fun showNativeReminderBubble(id: String, title: String, text: String, isPlan: Boolean) {
        val v = reminderView ?: return
        val p = reminderParams ?: return
        try {
            val (sw, sh) = screenSize()
            val d = resources.displayMetrics.density
            val winW = (280 * d).toInt().coerceAtMost(sw)
            p.width = winW
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            p.gravity = Gravity.TOP or Gravity.START
            // 先落屏内安全位（宠物上方会在下一行 updateReminderBubblePosition 贴过去）
            p.x = (sw - winW).coerceAtLeast(0)
            p.y = 0
            val card = buildReminderCard(id, title, text, isPlan)
            reminderCard = card
            v.removeAllViews()
            v.addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            activeReminderId = id
            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            updateReminderBubblePosition()
            Log.i("dsh-pet", "原生常驻提醒气泡已显示(跟随宠物): $title")
        } catch (e: Exception) {
            Log.w("dsh-pet", "原生气泡显示失败（仍有通知兜底）: ${e.message}")
        }
    }
    /** 让原生气泡跟随宠物本体（最大命中块 rectX/Y/W/H）：X 居中、Y 在宠物上方（越界则翻到下方）。
     *  由 applyTouchLayerRects 每帧调用，宠物移动时气泡实时刷新位置。 */
    private fun updateReminderBubblePosition() {
        val v = reminderView ?: return
        val p = reminderParams ?: return
        if (activeReminderId == null) return
        try {
            val (sw, sh) = screenSize()
            val d = resources.displayMetrics.density
            val winW = (280 * d).toInt().coerceAtMost(sw)
            p.width = winW
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            p.gravity = Gravity.TOP or Gravity.START
            val gap = (10 * d).toInt()
            val cardH = v.height.coerceAtLeast(0)
            var bx = rectX + rectW / 2 - winW / 2
            bx = bx.coerceIn(0, (sw - winW).coerceAtLeast(0))
            var by = rectY - cardH - gap
            if (by < 0) by = rectY + rectH + gap   // 上方放不下就翻到宠物下方
            by = by.coerceIn(0, (sh - 1).coerceAtLeast(0))
            if (p.x == bx && p.y == by) return
            p.x = bx
            p.y = by
            wm.updateViewLayout(v, p)
        } catch (_: Exception) {}
    }

    /** 关闭原生气泡：收回 1×1、清卡。 */
    private fun hideReminderBubble() {
        val v = reminderView ?: return
        val p = reminderParams ?: return
        try {
            p.x = 0; p.y = 0; p.width = 1; p.height = 1
            v.removeAllViews()
            reminderCard = null
            activeReminderId = null
            try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            Log.i("dsh-pet", "原生提醒气泡已关闭")
        } catch (e: Exception) {
            Log.w("dsh-pet", "关闭原生气泡失败: ${e.message}")
        }
    }

    private fun addTouchLayer(): View {
        val v = View(this)
        v.setBackgroundColor(0x00000000)
        v.setOnTouchListener { view, ev -> forwardTouch(view, ev); true }
        val p = WindowManager.LayoutParams(
            1,
            1,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // 与渲染窗口保持同一套布局语义：不加 NO_LIMITS 时，系统会把悬浮窗
                // 往「安全区」里塞（避开状态栏 / 导航栏 / 刘海），params.x/y 与实际落点
                // 差一段，触摸注入就整体偏移——「点碎碎念实际点到对话」。
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 不吃任何 insets：位置完全由我们给的 x/y 决定
                setFitInsetsTypes(0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(v, p)
        } catch (e: Exception) {
            Log.w("dsh-pet", "添加触摸层失败: ${e.message}")
        }
        touchViews.add(v)
        touchParamsList.add(p)
        return v
    }
    /**
     * 菜单捕获层：独立全屏透明窗口（不占触摸窗口池），菜单打开时铺全屏，
     * 触摸点落在菜单矩形内 → 正常转发（菜单可操作）；矩形外 → 注入合成 mousedown
     * 触发 JS 侧 document 处理器逐级收起。初始 1×1 @(0,0)，不挡任何东西。
     */
    private fun addCaptureLayer() {
        val v = View(this)
        v.setBackgroundColor(0x00000000)
        v.setOnTouchListener { _, _ -> false }
        val p = WindowManager.LayoutParams(
            1,
            1,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(v, p)
            captureView = v
            captureParams = p
        } catch (e: Exception) {
            Log.w("dsh-pet", "添加菜单捕获层失败: ${e.message}")
            captureView = null
            captureParams = null
        }
    }

    /**
     * 渲染窗口 / 触摸层的窗口类型。
     *
     * 完全体模式用 [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]：
     * com.android.server.wm.WindowState 会按 `InputMonitor.isTrustedOverlay(type)` 把它标记成
     * trustedOverlay，而**可信覆盖层不参与 Android 12+ 的「不可信触摸屏蔽」**——所以 alpha
     * 可以给到 1.0，桌面照常可点。这就是悬浮球 / 输入法「完全不透明又不挡桌面」的机制：
     * 它们要么是小窗口，要么是可信覆盖层。
     */
    private fun overlayType(): Int =
        if (trustedOverlayWanted() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            legacyOverlayType()

    /** 普通悬浮窗类型（拿不到无障碍时的唯一选择） */
    private fun legacyOverlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun loadRenderer() {
        webView?.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    /**
     * WebViewAssetLoader：把静态素材路径映射到 assets。API 路由（/dsh-pet-7340/state 等）由
     * JS 侧 installFetch 拦截，不落到这里；这里只服务 <script>/<img>/<video>/@font-face 这类
     * 不走 fetch 的请求。
     *   /assets/                 → assets/                （index.html、dsh-pet.js、config.jsonc）
     *   /dsh-pet-7340/font/      → assets/fonts/
     *   /dsh-pet-7340/pic/memes/ → assets/memes/
     *   /dsh-pet-7340/pic/       → assets/pic/
     *   /dsh-pet-7340/thumb/     → assets/webm/           （host 转发 realFetch('webm/..') → /assets/webm/..）
     */
    private fun buildAssetLoader(): WebViewAssetLoader {
        /** 去掉中间的 assetRoot 段："memes/main/xxx.png" → "memes/xxx.png"（多宠共用扁平素材目录时用） */
        fun stripFirstSegment(p: String): String {
            // 注意：必须剥掉**中间**的 assetRoot 段（如 "main"），保留首段目录名（memes/pic/webm）
            // 旧实现 p.substring(i+1) 剥掉的是首段，得到 "main/xxx.png"，永远打不开扁平素材
            val i1 = p.indexOf('/')
            if (i1 <= 0) return p
            val i2 = p.indexOf('/', i1 + 1)
            if (i2 <= 0 || i2 >= p.length - 1) return p
            return p.substring(0, i1 + 1) + p.substring(i2 + 1)
        }
        val mime = mapOf(
            "html" to "text/html", "js" to "text/javascript", "jsonc" to "text/plain",
            "json" to "application/json", "png" to "image/png", "ttf" to "font/ttf",
            "webm" to "video/webm", "gif" to "image/gif", "css" to "text/css",
        )
        fun handler(root: String) = WebViewAssetLoader.PathHandler { path ->
            val rel = path.trimStart('/')
            // assets.open 不接受前导 "/"，root 为空时（=assets 根）只取 rel 本身
            val assetPath = if (root.isEmpty()) rel else "$root/$rel"
            fun respond(p: String): WebResourceResponse? = try {
                val stream = assets.open(p)
                val ext = p.substringAfterLast('.', "").lowercase()
                WebResourceResponse(mime[ext] ?: "application/octet-stream", null, stream)
            } catch (e: Exception) {
                null
            }
            // 直接按映射表路径打开；失败时去掉 URL 路径首段再试一次：
            // JS 侧 <img src="/dsh-pet-7340/pic/memes/<assetRoot>/<表情名>.png"> 会带上宠物 id
            // （主宠="main"），而 assets/memes/ 是扁平目录，首次 open 必失败 → 剥掉 assetRoot 重试
            respond(assetPath) ?: respond(stripFirstSegment(assetPath))
        }
        return WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", handler(""))                 // → assets 根
            .addPathHandler("/dsh-pet-7340/font/", handler("fonts"))
            .addPathHandler("/dsh-pet-7340/pic/memes/", handler("memes"))
            .addPathHandler("/dsh-pet-7340/pic/", handler("pic"))
            .addPathHandler("/dsh-pet-7340/thumb/", handler("webm"))
            .build()
    }

    // ------------------------------------------------------------ 触摸注入

    /**
     * 触摸层 View 的触摸 → 换算成屏幕坐标 → 注入渲染 WebView。
     *
     * 触摸层窗口本身就等于命中区，所以这里**全部消费**（返回 true）是安全的：
     * 手指落在命中区内本来就该由宠物处理。
     */
    private fun forwardTouch(view: View, ev: MotionEvent) {
        val wv = webView ?: return
        // 补偿量必须**实测**，不能按 params.x/y 推算：悬浮窗的最终落点会被状态栏、
        // 导航栏、刘海裁切等系统 insets 二次修正，params 只是「请求值」。读两个 View
        // 的真实屏幕位置求差，系统怎么挪都能对齐。多窗口下每个窗口各自算自己的偏移。
        var dx = 0f
        var dy = 0f
        run {
            val a = IntArray(2)
            val b = IntArray(2)
            view.getLocationOnScreen(a)
            wv.getLocationOnScreen(b)
            dx = (a[0] - b[0]).toFloat()
            dy = (a[1] - b[1]).toFloat()
        }
        val copy = MotionEvent.obtain(ev)
        copy.offsetLocation(dx, dy)
        try {
            var handled = wv.dispatchTouchEvent(copy)
            if (!handled && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                // 兜底：dispatchTouchEvent 被 ViewGroup 层吃掉时直接喂 onTouchEvent
                handled = wv.onTouchEvent(copy)
            }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && downLogCount < 8) {
                downLogCount++
                Log.i(
                    "dsh-pet",
                    "forward DOWN handled=$handled @(${copy.x},${copy.y}) 窗口=($rectX,$rectY ${rectW}x$rectH)"
                )
            }
        } catch (e: Exception) {
            Log.w("dsh-pet", "dispatchTouchEvent 失败: ${e.message}")
        } finally {
            copy.recycle()
        }
    }

    /**
     * JS 上报命中区（单块兼容入口）：**视口归一化比例（0..1）**。
     *
     * 旧版渲染层（v1.0.5 及以前）只会上报「所有命中元素的并集」一块，这里保留为兼容路径：
     * 内部转成单元素列表，交给 [applyTouchLayerRects]。
     */
    fun applyTouchLayerRect(nx: Double, ny: Double, nw: Double, nh: Double) {
        applyTouchLayerRects(listOf(doubleArrayOf(nx, ny, nw, nh)))
    }

    /**
     * JS 上报命中区（多块）：`[{"x":..,"y":..,"w":..,"h":..}, ...]`，视口归一化比例。
     *
     * **为什么必须多块**：单窗口方案只能报「并集」。宠物在屏幕下方、气泡/菜单在上方时，
     * 并集的包围盒是一条横跨整屏的大矩形（面积常到 70~90%），触摸层把桌面的事件全部吃掉，
     * 用户看到的就是「退到桌面后只能操作桌宠，其它 App 点不动」——即使并集里的空白区域
     * 根本没有元素。多窗口后每块窗口都只等于一个元素的真实矩形，空白永远不落在触摸层里，
     * 这是结构性的修复，而不是靠阈值兜底。
     */
    fun applyTouchLayersJson(json: String) {
        val rects = ArrayList<DoubleArray>(MAX_TOUCH_LAYERS)
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                if (rects.size >= MAX_TOUCH_LAYERS) break
                val o = arr.optJSONObject(i) ?: continue
                rects.add(
                    doubleArrayOf(
                        o.optDouble("x", Double.NaN),
                        o.optDouble("y", Double.NaN),
                        o.optDouble("w", 0.0),
                        o.optDouble("h", 0.0),
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("dsh-pet", "命中区 JSON 解析失败: ${e.message}")
        }
        applyTouchLayerRects(rects)
    }

    /**
     * 长按菜单打开状态（JS 经 DshNative.setMenuOpen 上报，需求① 菜单外点击逐级收起）。
     * 打开时铺一个全屏透明捕获层窗口：触摸点在菜单矩形内 → 正常转发（菜单可操作）；
     * 在菜单矩形外 → 不转发原始触摸（避免误触宠物/拖拽），只向 WebView 注入合成
     * mousedown，触发 JS 侧 mh() 的 document mousedown 处理器：先收子列、再关整树。
     */
    fun setMenuOpen(open: Boolean) {
        menuOpen = open
        main.post {
            updateCaptureLayer(open)
        }
        if (open) {
            // 菜单刚挂载，读取根元素矩形（归一化比例）供外部点击判定
            main.post {
                webView?.evaluateJavascript(
                    "(function(){var m=document.querySelector('.dsh-pet-menu');" +
                        "if(!m)return 'null';var r=m.getBoundingClientRect();" +
                        "return JSON.stringify({x:r.left/window.innerWidth,y:r.top/window.innerHeight," +
                        "w:r.width/window.innerWidth,h:r.height/window.innerHeight});})()"
                ) { json ->
                    if (json == "null" || json.isNullOrBlank()) return@evaluateJavascript
                    try {
                        val o = JSONObject(json)
                        val geo = renderGeometry()
                        val (sw, sh) = screenSize()
                        val gx = geo[0]; val gy = geo[1]; val gw = geo[2]; val gh = geo[3]
                        menuRect = intArrayOf(
                            (gx + Math.round(o.optDouble("x", 0.0) * gw).toInt())
                                .coerceIn(0, (sw - 1).coerceAtLeast(0)),
                            (gy + Math.round(o.optDouble("y", 0.0) * gh).toInt())
                                .coerceIn(0, (sh - 1).coerceAtLeast(0)),
                            Math.round(o.optDouble("w", 0.0) * gw).toInt()
                                .coerceIn(1, (sw).coerceAtLeast(1)),
                            Math.round(o.optDouble("h", 0.0) * gh).toInt()
                                .coerceIn(1, (sh).coerceAtLeast(1)),
                        )
                    menuRectReady = true
                } catch (e: Exception) {
                        Log.w("dsh-pet", "读取菜单矩形失败: ${e.message}")
                    }
                }
            }
        } else {
            menuRectReady = false
        }
    }

    /** 菜单捕获层：打开铺全屏、关闭收回 1×1（独立窗口，不占触摸窗口池） */
    private fun updateCaptureLayer(open: Boolean) {
        val v = captureView ?: return
        val p = captureParams ?: return
        if (open) {
            val (sw, sh) = screenSize()
            p.x = 0
            p.y = 0
            p.width = sw
            p.height = sh
            v.setOnTouchListener { view, ev -> onCaptureTouch(view, ev) }
            try {
                wm.updateViewLayout(v, p)
            } catch (e: Exception) {
                Log.w("dsh-pet", "捕获层铺开失败: ${e.message}")
            }
        } else {
            p.x = 0
            p.y = 0
            p.width = 1
            p.height = 1
            v.setOnTouchListener { _, _ -> false }
            try {
                wm.updateViewLayout(v, p)
            } catch (e: Exception) {
                Log.w("dsh-pet", "捕获层收回失败: ${e.message}")
            }
        }
    }

    /** 当前活跃提醒气泡的 id（点按跳提醒列表定位用）；null = 无活跃气泡。 */
    @Volatile
    private var activeReminderId: String? = null

    /** 渲染页是否已加载完（reminder.js 已挂 window.__dshReminderBubble）。
     *  未到点前 WebView 可能尚未就绪：到点触发时若未就绪，气泡先暂存 [pendingReminderBubbles]，
     *  等 onPageFinished 再补发——否则 evaluateJavascript 调不到函数，气泡直接丢失（v1.3.0 bug）。 */
    @Volatile
    private var webViewPageReady = false
    /** 待补发的提醒气泡（页面就绪后 flush）。每条 = (id, title, text, isPlan)。 */
    private val pendingReminderBubbles = ArrayList<Array<String>>()

    /** 捕获层触摸分发：菜单外 DOWN → 注入合成 mousedown 让 JS 收起；菜单内 → 正常转发 */
    private fun onCaptureTouch(view: View, ev: MotionEvent): Boolean {
        if (!menuOpen) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val sx = ev.rawX
                val sy = ev.rawY
                // menuRect 未就绪（evaluateJavascript 异步回调还没回来）时，
                // 一律按「菜单内」转发，避免把菜单内点击误判成外点触发逐级收起
                val inside = !menuRectReady ||
                    (sx >= menuRect[0] && sx <= menuRect[0] + menuRect[2] &&
                        sy >= menuRect[1] && sy <= menuRect[1] + menuRect[3])
                if (!inside) {
                    captureConsume = true
                    injectMenuOutsideClick(sx, sy)
                } else {
                    captureConsume = false
                    forwardTouch(view, ev)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consume = captureConsume
                captureConsume = false
                if (!consume) forwardTouch(view, ev)
                return true
            }
            else -> {
                if (!captureConsume) forwardTouch(view, ev)
                return true
            }
        }
    }

    /** 注入合成 mousedown（DOWN+UP）到 WebView 指定屏幕坐标，触发 JS 外部点击收起 */
    /** 菜单外点击注入：向 JS 侧 document dispatch 一个合成 mousedown（捕获阶段）。
     *  不走 WebView 指针追踪，不会触发菜单容器 mouseleave 导致 200ms 定时器全关。
     *  JS 侧 mh() 的 x 处理器：!u.contains(target) → 有子列则 c() 收子列，无子列则 _() 关整树。
     *  所以每次点击外部的效果：先收子列（若有）、再关主菜单（无子列时）——逐级收起。 */
    private fun injectMenuOutsideClick(sx: Float, sy: Float) {
        val wv = webView ?: return
        val script =
            "document.dispatchEvent(new MouseEvent('mousedown', {" +
            "bubbles:true, cancelable:true, view:document.defaultView, " +
            "clientX:" + sx + ", clientY:" + sy + "}))"
        try {
            wv.evaluateJavascript(script, null)
        } catch (e: Exception) {
            Log.w("dsh-pet", "注入菜单外点击失败: ${e.message}")
        }
    }

    /**
     * 把归一化矩形列表落到触摸层窗口池。
     *
     * 换算只在这里做（JS 侧不知道设备 px，Kotlin 侧不知道 CSS px，中间用比例对齐）：
     *  · 隐藏态 / 空列表 → 所有窗口收成 1×1@(0,0)，彻底不挡触摸
     *  · 比例基准 = 渲染 WebView 的真实屏幕几何（renderGeometry），不是整屏尺寸
     *  · 位置/尺寸没变化的窗口不做 updateViewLayout（避免每帧重排 WindowManager）
     */
    private fun applyTouchLayerRects(rawRects: List<DoubleArray>) {
        // 任何一次上报（含心跳）都刷新这个时间戳，看门狗靠它判断渲染层是否停摆
        lastTouchReportAt = SystemClock.uptimeMillis()
        main.post {
            if (touchViews.isEmpty()) return@post
            // 穿透模式：忽略所有 JS 上报，保持 1×1（屏幕整块穿透给下层 App）
            if (clickThrough) return@post
            val (sw, sh) = screenSize()
            val geo = renderGeometry()
            val gx = geo[0]
            val gy = geo[1]
            val gw = geo[2]
            val gh = geo[3]
            val pxRects = ArrayList<IntArray>(MAX_TOUCH_LAYERS)
            if (!petHidden && sw > 0 && sh > 0 && gw > 0 && gh > 0) {
                for (r in rawRects) {
                    if (pxRects.size >= MAX_TOUCH_LAYERS) break
                    val nx = r[0]
                    val ny = r[1]
                    val nw = r[2]
                    val nh = r[3]
                    if (!nx.isFinite() || !ny.isFinite() || !nw.isFinite() || !nh.isFinite()) continue
                    if (nw <= 0.0 || nh <= 0.0) continue
                    val x = (gx + Math.round(nx * gw).toInt()).coerceIn(0, (sw - 1).coerceAtLeast(0))
                    val y = (gy + Math.round(ny * gh).toInt()).coerceIn(0, (sh - 1).coerceAtLeast(0))
                    val w = Math.round(nw * gw).toInt().coerceIn(1, (sw - x).coerceAtLeast(1))
                    val h = Math.round(nh * gh).toInt().coerceIn(1, (sh - y).coerceAtLeast(1))
                    // 单块保险丝：任何一块都不许超过屏幕这个比例。正常宠物在 10% 上下、
                    // 长按菜单与气泡也远小于 30%——超了说明 JS 侧量到了异常元素（隐藏面板
                    // 未过滤 / 元素被 transform 拉成整屏），宁可这一块点不到也不能吃掉桌面。
                    val areaRatio = w.toLong() * h.toLong() * 1.0 / (sw.toLong() * sh.toLong() * 1.0)
                    if (areaRatio > MAX_TOUCH_AREA_RATIO) {
                        Log.w(
                            "dsh-pet",
                            "命中块过大 ${w}x$h (${(areaRatio * 100).toInt()}%)，丢弃该块防死区"
                        )
                        continue
                    }
                    pxRects.add(intArrayOf(x, y, w, h))
                }
            }
            // 记录最大一块（日志 / 看门狗面积判断）
            var maxArea = -1L
            for (r in pxRects) {
                val a = r[2].toLong() * r[3].toLong()
                if (a > maxArea) {
                    maxArea = a
                    rectX = r[0]
                    rectY = r[1]
                    rectW = r[2]
                    rectH = r[3]
                }
            }
            if (pxRects.isEmpty()) {
                rectX = 0
                rectY = 0
                rectW = 1
                rectH = 1
            }
            for (i in touchViews.indices) {
                val p = touchParamsList[i]
                val r = if (i < pxRects.size) pxRects[i] else null
                val x = r?.get(0) ?: 0
                val y = r?.get(1) ?: 0
                val w = r?.get(2) ?: 1
                val h = r?.get(3) ?: 1
                if (p.x == x && p.y == y && p.width == w && p.height == h) continue
                p.x = x
                p.y = y
                p.width = w
                p.height = h
                try {
                    wm.updateViewLayout(touchViews[i], p)
                } catch (e: Exception) {
                    Log.w("dsh-pet", "touch updateViewLayout 失败: ${e.message}")
                }
            }
            // 原生气泡跟随宠物：每帧按最大命中块刷新位置（宠物移动时实时贴靠）
            updateReminderBubblePosition()
            if (touchLogCount < 8) {
                touchLogCount++
                val desc = pxRects.joinToString(" ") { "${it[2]}x${it[3]}@(${it[0]},${it[1]})" }
                Log.i(
                    "dsh-pet",
                    "touch layers=${pxRects.size} [$desc] 屏幕=${sw}x$sh 隐藏=$petHidden"
                )
            }
        }
    }

    /** 真实屏幕尺寸（px）——归一化坐标的换算基准 */
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
        }
        val d = resources.displayMetrics
        return d.widthPixels to d.heightPixels
    }

    /**
     * 渲染 WebView 在屏幕上的真实几何 [left, top, w, h]。
     *
     * 这是 JS 视口坐标的唯一可信基准：命中区比例 × 这组尺寸 + 这组原点，才与
     * getBoundingClientRect 完全同源。WebView 还没 attach 时退回整屏几何。
     */
    private fun renderGeometry(): IntArray {
        val wv = webView
        if (wv != null && wv.width > 0 && wv.height > 0) {
            val loc = IntArray(2)
            wv.getLocationOnScreen(loc)
            return intArrayOf(loc[0], loc[1], wv.width, wv.height)
        }
        val (sw, sh) = screenSize()
        return intArrayOf(0, 0, sw, sh)
    }

    // -------------------------------------------------------------- 桥调用

    /** 把 LLM 结果回调给 JS：window.__dshLlmResult(id, json) */
    fun deliverLlmResult(id: String, json: String) {
        val script = "window.__dshLlmResult && window.__dshLlmResult(" +
            JSONObject.quote(id) + "," + JSONObject.quote(json) + ")"
        main.post {
            webView?.evaluateJavascript(script, null)
        }
    }

    /** 设置页保存后主动刷新桌宠配置 */
    fun reloadConfig() {
        applyWindowOpacity()
        main.post {
            webView?.evaluateJavascript("window.__dshReloadConfig && window.__dshReloadConfig()", null)
        }
    }

    /**
     * 输入焦点：渲染窗口平时是 FLAG_NOT_FOCUSABLE（不抢桌面焦点），这会让 WebView 内的
     * <input> 点了也拿不到焦点、软键盘不弹——聊天输入框「点不到」的真因之一。
     *
     * JS 在输入框聚焦/失焦时回调这里：聚焦时临时摘掉 NOT_FOCUSABLE 并显式唤起输入法，
     * 失焦后立刻恢复，避免悬浮窗长期霸占焦点。
     */
    fun setFocusMode(on: Boolean) {
        main.post {
            val wv = webView ?: return@post
            if (!::renderParams.isInitialized) return@post
            val p = renderParams
            val notFocusable = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
            if (on && notFocusable) {
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else if (!on && !notFocusable) {
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            } else {
                // 状态已经正确，只补一次输入法动作
            }
            try {
                wm.updateViewLayout(wv, p)
            } catch (e: Exception) {
                Log.w("dsh-pet", "焦点切换失败: ${e.message}")
                return@post
            }
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (on) {
                wv.requestFocus()
                imm?.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT)
            } else {
                imm?.hideSoftInputFromWindow(wv.windowToken, 0)
            }
            Log.i("dsh-pet", "输入焦点 = $on")
        }
    }

    fun hapticFeedback() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") v.vibrate(28)
        }
    }

    fun openSettingsActivity() {
        val i = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    /** 带「定位到某条提醒」地打开设置页提醒 tab（气泡点击跳列表用）。 */
    fun openRemindersPage(focusId: String? = null) {
        val i = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SettingsActivity.EXTRA_REMINDER_TAB, true)
        if (!focusId.isNullOrEmpty()) i.putExtra(SettingsActivity.EXTRA_FOCUS_REMINDER_ID, focusId)
        startActivity(i)
    }

    /**
     * 到点触发提醒：把 ids 中 pending 且已过点的项流转并落盘（memo→done / plan→triggered），
     * 对**本次实际触发**的项逐条弹气泡（宠物旁），并给一条系统通知兜底（气泡被穿透/隐藏时仍能收到）。
     * 气泡点按 → 跳提醒列表（带 focus id）。
     */
    fun fireReminders(ids: List<String>) {
        if (ids.isEmpty()) return
        // 到点常在锁屏/灭屏时触发（精确闹钟 RTC_WAKEUP），此时 WebView 可能正被
        // pauseTimers 冻结（灭屏/隐藏时冻结 JS 定时器省电）。冻结态下 reminder.js 的
        // rAF/定时上报会被卡住 → 气泡画了但不显示。发气泡前先恢复计时器，保证可见。
        main.post {
            try { webView?.resumeTimers() } catch (_: Exception) {}
            try { webView?.onResume() } catch (_: Exception) {}
        }
        val fired = ReminderScheduler.fireDue(this, ids)
        Log.i("dsh-pet", "提醒到点 fire: 请求 ${ids.size} 条，实际触发 ${fired.size} 条")
        for (r in fired) {
            val isPlan = r is Reminder.Plan
            val text = if (isPlan) "计划「${r.title}」到了，逐项完成吧" else "备忘：${r.title}"
            // 气泡（可点，点按跳列表并定位该条）——按该条 showBubble 开关生效
            if (Reminder.showBubble(r)) {
                main.post {
                    showReminderBubble(r.id, r.title, text, isPlan)
                }
            }
            // 系统通知兜底（气泡在穿透/隐藏态下不可见时仍可达）——按该条 showNotification 开关生效
            if (Reminder.showNotification(r)) {
                showReminderNotification(r.id, r.title, text)
            }
        }
        // 触发后重挂其余待触发项（幂等）
        main.postDelayed({ ReminderScheduler.armAll(this) }, 500L)
    }

    /** 弹一条常驻提醒气泡（原生独立窗口，不依赖 WebView 的 alpha/触摸/定时器）。
     *  到点常在锁屏/灭屏时触发，WebView 可能被 pauseTimers 冻结、alpha 被压在阈值下，
     *  DOM 气泡画了也看不见——改用原生小浮窗常驻，直到用户点「×」或点卡跳列表才关闭。 */
    private fun showReminderBubble(id: String, title: String, text: String, isPlan: Boolean) {
        showNativeReminderBubble(id, title, text, isPlan)
    }
    /** 旧的 JS 命中区上报路径（v1.3.2 起气泡改原生窗口，不再依赖 WebView DOM 命中区）。
     *  保留方法仅为 NativeBridge.reminderBubbleRect 兼容；实际收放走 hideReminderBubble/showNativeReminderBubble。 */
    fun applyReminderBubbleRect(json: String) {
        // no-op：原生气泡窗口独立管理，无需 JS 上报矩形
    }

    /** 立即向（已就绪的）WebView 注入一条气泡。 */
    private fun sendReminderBubbleNow(wv: WebView, id: String, title: String, text: String, isPlan: Boolean) {
        val safeTitle = title.replace(Regex("[\"\\\\]"), " ").replace('\n', ' ')
        val safeText = text.replace(Regex("[\"\\\\]"), " ").replace('\n', ' ')
        val script = "(function(){try{" +
            "if(window.__dshReminderBubble){window.__dshReminderBubble(" +
            "JSON.stringify({id:" + JSONObject.quote(id) + ", title:" + JSONObject.quote(safeTitle) +
            ", text:" + JSONObject.quote(safeText) + ", plan:" + isPlan + "});return;}" +
            "}catch(e){}}" +
            ")()"
        try {
            wv.evaluateJavascript(script, null)
        } catch (e: Exception) {
            Log.w("dsh-pet", "提醒气泡注入失败（降级为仅通知）: ${e.message}")
        }
    }

    /** 页面加载完成：置就绪 + 补发暂存气泡。由 WebViewClient.onPageFinished 调。 */
    fun onPageFinishedReminderFlush() {
        webViewPageReady = true
        val wv = webView ?: return
        val flushed = synchronized(pendingReminderBubbles) {
            val copy = pendingReminderBubbles.toList()
            pendingReminderBubbles.clear()
            copy
        }
        for (b in flushed) {
            sendReminderBubbleNow(wv, b[0], b[1], b[2], b[3] == "1")
        }
        if (flushed.isNotEmpty()) Log.i("dsh-pet", "页面就绪，补发 ${flushed.size} 条暂存提醒气泡")
    }

    /** 系统通知兜底：提醒到点发一条（点通知跳提醒列表定位该条）。 */
    private fun showReminderNotification(id: String, title: String, text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm)
            val i = Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SettingsActivity.EXTRA_REMINDER_TAB, true)
                .putExtra(SettingsActivity.EXTRA_FOCUS_REMINDER_ID, id)
            val pi = PendingIntent.getActivity(
                this, id.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // 稍后提醒（Snooze）：广播 → ReminderAlarmReceiver 记一条 now+10min 的再次提醒
            val snoozeIntent = Intent(this, ReminderAlarmReceiver::class.java).apply {
                action = ReminderAlarmReceiver.ACTION_SNOOZE
                putExtra(ReminderAlarmReceiver.EXTRA_ID, id)
                putExtra(ReminderAlarmReceiver.EXTRA_SNOOZE_MIN, 10)
            }
            val snoozePi = PendingIntent.getBroadcast(
                this, ("snooze_" + id).hashCode(), snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // 免打扰时段：走静音渠道（不响铃不震动，仍留通知，起来后能看到）
            val channel = if (inQuietHours()) CHANNEL_REMINDER_SILENT else CHANNEL_REMINDER
            val b = Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("$title")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "稍后 10 分钟", snoozePi).build())
            nm.notify(0x60000000 + (id.hashCode() and 0x3fffffff), b.build())
        } catch (e: Exception) {
            Log.w("dsh-pet", "提醒通知发送失败: ${e.message}")
        }
    }

    fun showPetNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val icon = android.R.drawable.stat_notify_chat
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify(nextNotifyId(), n)
    }

    // --------------------------------------------------------- 前台通知

    private fun startForegroundSafe() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        // 先试完整通知（含自定义展开视图）；任一环节失败则降级为最小通知，
        // 保证 startForeground 成功、服务不崩（闪退根因由全局 DshApp 捕获落盘，
        // 降级原因也一并写进崩溃日志，下次进设置页可查）。
        val n = try {
            buildForegroundNotification()
        } catch (e: Exception) {
            appendCrashDiagnosis("buildForegroundNotification 失败(降级最小通知)", e)
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("桌宠运行中 · 通知降级显示")
                .setOngoing(true)
                .build()
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(FOREGROUND_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(FOREGROUND_ID, n)
            }
        } catch (e: Exception) {
            // 前台服务注册失败（个别 ROM/权限）：记录但不崩，渲染窗口已单独创建
            appendCrashDiagnosis("startForeground 失败", e)
        }
    }
    /** 把「没崩但异常」的诊断也写进同一份崩溃日志（filesDir/dshcrash.log，
     *  与 DshApp 的全局崩溃捕获共用一个文件，设置页统一展示）。 */
    private fun appendCrashDiagnosis(tag: String, e: Throwable) {
        Log.e("dsh-pet", tag + ": " + e.javaClass.simpleName + " - " + e.message)
        try {
            val ts = System.currentTimeMillis()
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val body = "\n===== " + tag + " @" + ts + " =====\n" + sw.toString()
            // 复用 DshApp 的多路写入，与全局崩溃捕获共用同一份文件
            val app = application as? DshApp
            if (app != null) {
                app.writeCrashLog(body)
            } else {
                // 兑底：应用没挂 DshApp 时直接写 filesDir
                java.io.FileWriter(java.io.File(filesDir, "dshcrash.log"), true).use { w -> w.write(body) }
            }
        } catch (ignored: Exception) {
        }
    }

    /**
     * 常驻通知：标题 + 状态 + 4 个动作（显示/隐藏、归位、吃白饭 toggle、穿透 toggle）。
     *
     * 「暂时隐藏」在渲染层是把 display 改成 none——命中区没了、气泡也没了，宠物就彻底看不见。
     * 手机上没有右键菜单可点、也没有桌面可以去翻，**通知栏就是唯一还摸得到的入口**，
     * 所以这里必须给一个能把它叫回来的按钮，否则用户只能去设置页停服务再开。
     * 通知正文带当前 versionName，覆盖安装后用于一眼确认是不是新代码进程在发通知。
     */
    private fun buildForegroundNotification(): Notification {
        val settingsPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggleLabel = if (petHidden) "显示桌宠" else "暂时隐藏"
        val togglePi = PendingIntent.getService(
            this, 1,
            Intent(this, PetService::class.java)
                .setAction(if (petHidden) ACTION_SHOW_PET else ACTION_HIDE_PET),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sizePct = Math.round(currentSizeRatio() * 100)
        // 把当前 versionName 写进通知文本：一眼确认此刻发通知的是新代码进程还是旧进程
        // （覆盖安装后若通知里仍是旧版本号/无版本，说明是旧进程的 ongoing 通知未刷掉）。
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val text = if (petHidden) "桌宠已隐藏 · v$ver" else "桌宠运行中 · v$ver · 大小 $sizePct%"
        // 保活状态说明：白名单未开时给提示，引导去电池优化设置页（点通知即跳系统白名单页）
        // 需求② 归位按钮：与右键菜单「回到初始位置」同效果（JS 侧遍历所有归位回调）
        val homePi = PendingIntent.getService(
            this, 3,
            Intent(this, PetService::class.java).setAction(ACTION_PET_HOME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 「吃白饭」toggle 按钮：触发 JS 侧 window.__dshBaifanToggle（在跑→停、没跑→开）
        val baifanPi = PendingIntent.getService(
            this, 4,
            Intent(this, PetService::class.java).setAction(ACTION_BAIFAN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 「吃白饭/停止吃白饭」toggle 按钮：标签随 baifanRunning（JS 经 setBaifanRunning 上报）动态切换
        val baifanLabel = if (baifanRunning) "停止吃白饭" else "吃白饭"
        // 「穿透/停止穿透」toggle 按钮：翻转点击穿透模式
        val ctLabel = if (clickThrough) "停止穿透" else "穿透"
        val ctPi = PendingIntent.getService(
            this, 6,
            Intent(this, PetService::class.java).setAction(ACTION_CLICK_THROUGH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 需求③ 自定义展开视图：标题/状态 → 「大小：」+ 滑块进度条(ProgressBar) + 百分比。
        // 系统限制：RemoteViews 无法监听滑动事件，故用 11 个透明点击热区
        // 叠在进度条上方，点击任意段直接设值；视觉上是连贯的滑块进度条。
        val rv = RemoteViews(packageName, R.layout.notification_size)
        rv.setTextViewText(R.id.nt_title, getString(R.string.app_name))
        rv.setTextViewText(R.id.nt_text, text)
        // 当前真实百分比（如 50%），随 sizeRatio 刷新
        val pct = Math.round(currentSizeRatio() * 100)
        rv.setTextViewText(R.id.nt_size_value, "$pct%")
        // 纯展示 + 点击热区：进度条显示当前档位，11 个透明热区绑 PendingIntent 设值
        val ratio = currentSizeRatio()
        val currentLevel = Math.round(((ratio - 0.40) / 0.05)).toInt().coerceIn(0, 10)
        rv.setInt(R.id.nt_seekbar, "setProgress", currentLevel)
        // 11 个透明热区绑定 PendingIntent：携带该档对应比例（0.40 + i*0.05）
        val segIds = intArrayOf(
            R.id.nt_seg_1, R.id.nt_seg_2, R.id.nt_seg_3, R.id.nt_seg_4, R.id.nt_seg_5,
            R.id.nt_seg_6, R.id.nt_seg_7, R.id.nt_seg_8, R.id.nt_seg_9, R.id.nt_seg_10, R.id.nt_seg_11,
        )
        for (i in segIds.indices) {
            val target = 0.40 + i * 0.05
            val pi = PendingIntent.getService(
                this,
                100 + i,
                Intent(this, PetService::class.java)
                    .setAction(ACTION_PET_SIZE_SET)
                    .putExtra(EXTRA_PET_SIZE_RATIO, target),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(segIds[i], pi)
        }
        // 展开态按钮：与收起态 action 语义一致（toggle/归位/吃白饭/穿透）
        rv.setOnClickPendingIntent(R.id.nt_btn_toggle, togglePi)
        rv.setOnClickPendingIntent(R.id.nt_btn_home, homePi)
        rv.setOnClickPendingIntent(R.id.nt_btn_baifan, baifanPi)
        rv.setOnClickPendingIntent(R.id.nt_btn_ct, ctPi)
        rv.setTextViewText(R.id.nt_btn_toggle, toggleLabel)
        rv.setTextViewText(R.id.nt_btn_baifan, baifanLabel)
        rv.setTextViewText(R.id.nt_btn_ct, ctLabel)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(settingsPi)
            .setOngoing(true)
        // 只挂展开视图（setCustomBigContentView）：收起时显示标准视图（图标+标题+按钮），
        // 展开时显示滑块进度条。不能 setCustomContentView（会替换收起视图导致图标/按钮消失）。
        builder.setCustomBigContentView(rv)
        // 底部动作按钮：Android 最多显示 3 个，第 4+ 个折叠进「…」
        // 吃白饭 toggle + 穿透 toggle 各占 1 个；显示/隐藏 + 归位占 2 个
        return builder
            .addAction(Notification.Action.Builder(null, toggleLabel, togglePi).build())
            .addAction(Notification.Action.Builder(null, "归位", homePi).build())
            .addAction(Notification.Action.Builder(null, baifanLabel, baifanPi).build())
            .addAction(Notification.Action.Builder(null, ctLabel, ctPi).build())
            .build()
    }

    private fun updateForegroundNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(FOREGROUND_ID, buildForegroundNotification())
        } catch (e: Exception) {
            Log.w("dsh-pet", "更新常驻通知失败: ${e.message}")
        }
    }
    /** 通知栏「大小」调节：把增减量交给 JS 侧的 window.__dshPetSize(delta)（它负责读/夹取/落盘/刷新），
     *  成功回调里刷新通知文本显示新百分比。每次步进 0.05（5% 屏宽）。 */
    private fun adjustPetSize(delta: Float) {
        val script = "window.__dshPetSize && window.__dshPetSize(" + delta + ")"
        main.post {
            webView?.evaluateJavascript(script) { _ ->
                updateForegroundNotification()
            }
        }
    }
    /** 通知栏进度条：按档位直接设桌宠大小（JS 侧 __dshPetSize 读/夹取/落盘/刷新）。
     *  与 adjustPetSize 不同：这里传「目标比例」而非增量，夹取区间同为 0.40~0.90。 */
    private fun setPetSizeRatio(ratio: Double) {
        val clamped = ratio.coerceIn(0.40, 0.90)
        val script = "window.__dshPetSize && window.__dshPetSize(" + clamped + ")"
        main.post {
            webView?.evaluateJavascript(script) { _ ->
                updateForegroundNotification()
            }
        }
    }
    /** 通知栏「吃白饭」：触发 JS 侧 window.__dshBaifanStart（baifan.js 独立入口）。
     *  睡眠中会被 baifan.js 内部探针拒绝；玩法期间动画暂停由 lock 机制天然满足。 */
    private fun startBaifan() {
        val script = "window.__dshBaifanToggle && window.__dshBaifanToggle()"
        main.post {
            webView?.evaluateJavascript(script) { _ ->
                Log.i("dsh-pet", "吃白饭 toggle 已触发（在跑→停、没跑→开）")
            }
        }
    }

    /** 停止吃白饭（通知栏「停止吃白饭」按钮触发 JS 侧 window.__dshBaifanStop）。 */
    private fun stopBaifan() {
        val script = "window.__dshBaifanStop && window.__dshBaifanStop()"
        main.post {
            webView?.evaluateJavascript(script) { _ ->
                Log.i("dsh-pet", "停止吃白饭已触发")
            }
        }
    }
    /** 读当前 sizeRatio（通知文本显示用；读不到回默认 0.42） */
    private fun currentSizeRatio(): Double {
        return try {
            JSONObject(Store.readUiSettings(this)).optDouble("sizeRatio", 0.42).coerceIn(0.40, 0.90)
        } catch (e: Exception) {
            0.42
        }
    }

    /** 显示 / 暂时隐藏桌宠（渲染窗口整体 GONE，服务与 WebView 都还在，恢复是瞬时的） */
    fun setPetHidden(hidden: Boolean) {
        main.post {
            if (petHidden == hidden) {
                updateForegroundNotification()
                return@post
            }
            petHidden = hidden
            Companion.isHidden = hidden
            webView?.visibility = if (hidden) View.GONE else View.VISIBLE
            if (hidden) {
                // 后台降载：冻结 JS 定时器/渲染与视频解码，多宠同时存在时省电省内存
                // （WebView.onPause 只停页面，pauseTimers 连共享的 JS 引擎一起冻结）
                try { webView?.onPause() } catch (e: Exception) { Log.w("dsh-pet", "webview onPause 失败: ${e.message}") }
                try { webView?.pauseTimers() } catch (e: Exception) { Log.w("dsh-pet", "webview pauseTimers 失败: ${e.message}") }
            }
            if (!hidden) {
                // 恢复后让渲染层重新上报命中区（隐藏期间它上报的都被丢掉了）；并恢复 JS/渲染
                try { webView?.resumeTimers() } catch (e: Exception) { Log.w("dsh-pet", "webview resumeTimers 失败: ${e.message}") }
                try { webView?.onResume() } catch (e: Exception) { Log.w("dsh-pet", "webview onResume 失败: ${e.message}") }
                webView?.evaluateJavascript(
                    "window.__dshReloadConfig && window.__dshReloadConfig()", null
                )
            } else {
                // 立刻收回触摸层，不等 JS 下一帧
                applyTouchLayerRect(0.0, 0.0, 0.0, 0.0)
            }
            updateForegroundNotification()
            Log.i("dsh-pet", if (hidden) "桌宠已隐藏" else "桌宠已显示")
        }
    }

    /**
     * 渲染窗口 alpha 的上限 —— Android 12 起「不可信触摸」阻断的硬约束。
     *
     * Android 12（SDK 31）在 InputDispatcher 里给每次触摸加了一道判定：
     * 若触摸目标窗口（这里是桌面 Launcher）之上压着**另一个应用的可见窗口**，且该窗口的
     * opacity 大于 Settings.Global 里的 `maximum_obscuring_opacity_for_touch`（默认 0.8），
     * 系统就认定这是点击劫持，**直接把这次触摸丢掉**，下层应用一个事件都收不到。
     *
     * 渲染窗口是全屏的，只要 alpha = 1.0，桌面上的任何一次点击都落在这层"100% 不透明遮挡"
     * 之下 → 全部被丢弃。这就是「桌宠能点能拖、桌宠之外全屏点不动」的真正原因：
     * v1.0.5 的并集窗口、v1.0.6 的多窗口池都只是在缩小**命中区**，
     * 而挡住桌面的从来不是触摸层，是这层全屏渲染窗口本身。
     *
     * 所以窗口 alpha 必须压在阈值以下（留 0.02 余量）。代价是宠物最浓只能到 ~78%，
     * 这是 Android 12+ 对普通应用（拿不到 INTERNAL_SYSTEM_WINDOW / trusted overlay）的硬限制。
     */
    private fun obscuringAlphaLimit(): Float {
        // 完全体模式：窗口是可信覆盖层，系统根本不看它的 opacity → 不需要压上限，直接 1.0
        if (trustedOverlayWanted()) return 1.0f
        val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.8f else 1.0f
        val raw = try {
            Settings.Global.getFloat(contentResolver, "maximum_obscuring_opacity_for_touch", fallback)
        } catch (e: Exception) {
            fallback
        }
        return (raw - 0.02f).coerceIn(0.30f, 1.0f)
    }

    /**
     * 透明度：**用窗口 alpha 而不是 DOM opacity**。
     *
     * 之前只在 JS 里给 #dsh-pet-app 贴 style.opacity，那条链路要等 evaluateJavascript 回到页面
     * 且根容器样式没被重挂覆盖，实际经常「拖了没反应」。窗口 alpha 由系统合成器直接应用，
     * 改完立刻可见，宠物/气泡/菜单一起变，也不怕渲染层什么时候重绘。
     *
     * 注意：窗口 alpha 同时是系统判定"是否遮挡"的依据，因此这里必须再压一道
     * [obscuringAlphaLimit]，用户把滑块拉满也只能到 ~78%。
     */
    private fun applyWindowOpacity() {
        if (!::renderParams.isInitialized) return
        val op = try {
            JSONObject(Store.readUiSettings(this)).optDouble("opacity", 1.0)
        } catch (e: Exception) {
            1.0
        }
        val want = op.coerceIn(0.30, 1.0).toFloat()
        val limit = obscuringAlphaLimit()
        val a = want.coerceAtMost(limit)
        main.post {
            if (webView == null) return@post
            if (kotlin.math.abs(renderParams.alpha - a) < 0.001f) return@post
            renderParams.alpha = a
            try {
                webView?.let { wm.updateViewLayout(it, renderParams) }
                Log.i("dsh-pet", "窗口透明度 = $a（期望 $want，遮挡上限 $limit）")
            } catch (e: Exception) {
                Log.w("dsh-pet", "透明度更新失败: ${e.message}")
            }
        }
    }

    /**
     * 触摸层看门狗。
     *
     * 命中区由渲染层 requestAnimationFrame 每帧上报，而 rAF 在应用退到后台后会被系统冻结：
     * 触摸层就**停在最后一次上报的矩形**上，那块区域之内再也点不动（用户反馈的「退到后台后
     * 桌面只能点宠物」）。这里只要超过 [TOUCH_REPORT_TIMEOUT_MS] 没收到上报（含 JS 侧 2 秒
     * 心跳），就把触摸层收回 1×1：宁可暂时点不到宠物，也不能把桌面锁死。JS 恢复上报后自动回来。
     */
    /**
     * 请求重建窗口（主线程）。
     *
     * 无障碍「刚开启 / 刚关闭」时窗口类型必须换：addView 之后类型改不了，只能拆了重建。
     * 重建会重载渲染层，宠物回到默认位置——只在用户手动切模式时发生，可以接受。
     */
    fun requestRebuild() {
        main.post { rebuildWindows() }
    }

    private fun rebuildWindows() {
        if (webView == null) return
        Log.i("dsh-pet", "重建窗口，类型 = ${overlayType()}（完全体=${trustedOverlayWanted()}）")
        try {
            webView?.let { wm.removeView(it) }
        } catch (e: Exception) {
            // 已被移除
        }
        for (v in touchViews) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
                // 同上
            }
        }
        touchViews.clear()
        touchParamsList.clear()
        try {
            captureView?.let { wm.removeView(it) }
        } catch (e: Exception) {
            // 已被移除
        }
        captureView = null
        captureParams = null
        try {
            webView?.destroy()
        } catch (e: Exception) {
            // 已销毁
        }
        webView = null
        // 换了宿主 Context，上一次的拒绝结论不再适用：允许按新类型重试一次
        trustedOverlayDenied = false
        createRenderWindow()
        createTouchWindow()
        loadRenderer()
        applyWindowOpacity()
        startTouchWatchdog()
    }

    private fun startTouchWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        val task = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                if (!petHidden && lastTouchReportAt > 0 &&
                    now - lastTouchReportAt > TOUCH_REPORT_TIMEOUT_MS
                ) {
                    // 渲染层停摆（退到桌面后 rAF 与定时器都被系统冻结）。
                    // **不能把窗口收回 1×1**：那样退到桌面后桌宠就彻底点不到了，而
                    // 「在桌面上能点桌宠」正是这个 App 的核心用途。窗口停在最后一次上报的
                    // 命中区上，那正是宠物当前所在位置，桌面其余区域本来就不受影响
                    // （FLAG_NOT_TOUCH_MODAL 会把窗口外的事件交还给桌面）。
                    // 这里只在面积明显异常时兜底，并把停摆记进日志便于现场诊断。
                    lastTouchReportAt = now
                    val (sw, sh) = screenSize()
                    val ratio = rectW.toLong() * rectH.toLong() * 1.0 /
                        (sw.toLong() * sh.toLong() * 1.0)
                    if (ratio > MAX_TOUCH_AREA_RATIO) {
                        Log.w(
                            "dsh-pet",
                            "触摸层停摆且面积异常 ${rectW}x$rectH (${(ratio * 100).toInt()}%)，收回 1×1"
                        )
                        applyTouchLayerRect(0.0, 0.0, 0.0, 0.0)
                    } else {
                        Log.i(
                            "dsh-pet",
                            "触摸层停摆，保留命中区 ${rectW}x$rectH @($rectX,$rectY)"
                        )
                    }
                }
                main.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        main.postDelayed(task, WATCHDOG_INTERVAL_MS)
    }

    /** 点击音效：开关在 ui-settings.json（用户可能在设置页关掉） */
    fun playClickSound() {
        val on = try {
            JSONObject(Store.readUiSettings(this)).optBoolean("clickSound", true)
        } catch (e: Exception) {
            true
        }
        if (!on) return
        main.post {
            // 双通道：系统按键音（走 AudioManager 的 touch sound 通道）+ ToneGenerator 合成哔声。
            // 只靠 ToneGenerator 时，部分 ROM 在 STREAM_MUSIC 上静默失效，表现就是「台词弹了但没声」。
            var played = false
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null) {
                    am.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.7f)
                    played = true
                }
            } catch (e: Exception) {
                Log.w("dsh-pet", "系统点击音失败: ${e.message}")
            }
            try {
                val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { tone = it }
                t.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                played = true
            } catch (e: Exception) {
                Log.w("dsh-pet", "点击音效失败: ${e.message}")
            }
            if (!played) Log.w("dsh-pet", "点击音效两个通道都失败")
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(ch)
            }
            // 提醒专用渠道：默认响铃 + 震动（用户可在系统里单独调），与桌宠常驻通知分开
            if (nm.getNotificationChannel(CHANNEL_REMINDER) == null) {
                val ch = NotificationChannel(
                    CHANNEL_REMINDER, "提醒", NotificationManager.IMPORTANCE_HIGH)
                ch.enableVibration(true)
                ch.vibrationPattern = longArrayOf(0, 200, 120, 200)
                ch.enableLights(true)
                nm.createNotificationChannel(ch)
            }
            if (nm.getNotificationChannel(CHANNEL_REMINDER_SILENT) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_REMINDER_SILENT, "提醒（静音）",
                        NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    /** 当前是否处于免打扰时段（设置页可配，默认 23:00–07:00 关闭）。 */
    private fun inQuietHours(): Boolean {
        val enabled = Store.prefBool(this, "quietEnabled", true)
        if (!enabled) return false
        val start = Store.prefInt(this, "quietStart", 23)
        val end = Store.prefInt(this, "quietEnd", 7)
        val h = java.time.LocalTime.now().hour
        return if (start <= end) h in start until end else (h >= start || h < end)
    }

    private fun nextNotifyId(): Int = (System.currentTimeMillis() % 100000).toInt()

    override fun onDestroy() {
        super.onDestroy()
        // 注销屏幕保活广播（生命周期与 Service 一致，避免泄漏）
        try {
            unregisterReceiver(screenKeepAliveReceiver)
        } catch (e: Exception) {
            // 未注册或已注销
        }
        // 保活强化收尾：先停自身资源（重挂闹钟循环 + 短 wakelock）
        reArmRunnable?.let { main.removeCallbacks(it) }
        reArmRunnable = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        // 只有「用户主动停止」才清保活闹钟（那时 wanted 已被置 false）；
        // 被系统/ROM 回收时 wanted 仍为 true，闹钟必须留着，否则就再也回不来了。
        if (!KeepAlive.isWanted(this)) KeepAlive.cancelAll(this)
        try {
            webView?.let { wm.removeView(it) }
            for (v in touchViews) {
                try {
                    wm.removeView(v)
                } catch (e: Exception) {
                    // 单个窗口可能已被移除，继续清下一个
                }
            }
        } catch (e: Exception) {
            // 窗口可能已被移除
        }
        touchViews.clear()
        touchParamsList.clear()
        try {
            captureView?.let { wm.removeView(it) }
        } catch (e: Exception) {
            // 已被移除
        }
        captureView = null
        captureParams = null
        webView?.destroy()
        webView = null
        try {
            tone?.release()
        } catch (e: Exception) {
            // 已释放
        }
        tone = null
        Companion.running = false
        Companion.isHidden = false
    }

    companion object {
        const val CHANNEL_ID = "dsh-pet-main"
        /** 提醒专用通知渠道（响铃 + 震动）。 */
        const val CHANNEL_REMINDER = "dsh-pet-reminder"
        /** 免打扰时段的静音提醒渠道（不出声不震动）。 */
        const val CHANNEL_REMINDER_SILENT = "dsh-pet-reminder-silent"
        const val FOREGROUND_ID = 7340

        /**
         * 触摸层窗口池容量 = 同时可铺开的命中块上限。
         *
         * 需要覆盖的真实命中元素：宠物本体(1) + 长按菜单列(1) + 对话气泡(1) + 可点气泡(1)，
         * 留 4 个余量给「多只宠物 / 气泡堆叠 / 设置浮层」这类临时态。窗口是预建的，
         * 容量只是数组上限，不占额外资源。
         */
        const val MAX_TOUCH_LAYERS = 8
        /** 命中区面积超过屏幕这个比例就视为异常上报（触摸层绝不能吃掉半屏以上） */
        const val MAX_TOUCH_AREA_RATIO = 0.90
        /** 看门狗检查间隔 / 上报超时阈值（JS 侧 2 秒心跳，留三倍余量） */
        const val WATCHDOG_INTERVAL_MS = 2000L
        const val TOUCH_REPORT_TIMEOUT_MS = 6000L

        /** 通知栏动作：暂时隐藏 / 显示桌宠 */
        const val ACTION_HIDE_PET = "com.dsh.pet.action.HIDE_PET"
        const val ACTION_SHOW_PET = "com.dsh.pet.action.SHOW_PET"
        /** 通知栏动作：归位（与右键菜单「回到初始位置」同一逻辑）/ 大小调节（±0.05 比例） */
        const val ACTION_PET_HOME = "com.dsh.pet.action.PET_HOME"
        const val ACTION_PET_SIZE_INC = "com.dsh.pet.action.PET_SIZE_INC"
        const val ACTION_PET_SIZE_DEC = "com.dsh.pet.action.PET_SIZE_DEC"
        /** 应用内右键菜单等动作：按目标比例设桌宠大小（EXTRA_PET_SIZE_RATIO，通知栏进度条已改为纯展示） */
        const val ACTION_PET_SIZE_SET = "com.dsh.pet.action.PET_SIZE_SET"
        /** 通知栏动作：吃白饭（触发 JS 侧 window.__dshBaifanStart，睡眠中会被拒绝） */
        const val ACTION_BAIFAN = "com.dsh.pet.action.BAIFAN"
        /** 通知栏动作：停止吃白饭（触发 JS 侧 window.__dshBaifanStop） */
        const val ACTION_BAIFAN_STOP = "com.dsh.pet.action.BAIFAN_STOP"
        /** 通知栏动作：穿透/停止穿透 toggle */
        const val ACTION_CLICK_THROUGH = "com.dsh.pet.action.CLICK_THROUGH"
        /** 提醒/计划到点：携带 [EXTRA_REMINDER_IDS]，拉起服务后 fire（弹气泡 + 改状态） */
        const val ACTION_REMINDER_FIRE = "com.dsh.pet.action.REMINDER_FIRE"
        const val EXTRA_REMINDER_IDS = "com.dsh.pet.extra.REMINDER_IDS"
        const val EXTRA_PET_SIZE_RATIO = "com.dsh.pet.extra.PET_SIZE_RATIO"

        /** 运行态单例引用（设置页保存后据此触发 reloadConfig） */
        @Volatile
        var instance: PetService? = null

        @Volatile
        var running: Boolean = false

        /**
         * 「已隐藏」状态的镜像值。
         *
         * 真值在实例的 petHidden（决定窗口 GONE 与触摸层收回），这里只是给设置页 / 通知栏
         * 读一个不用判空的副本；两处必须同时改，否则会出现「按钮写着显示桌宠、其实已经显示」。
         */
        @Volatile
        var isHidden: Boolean = false
    }

    // 让 companion 持有当前实例（onCreate 时登记）
    init {
        instance = this
        running = true
    }
}