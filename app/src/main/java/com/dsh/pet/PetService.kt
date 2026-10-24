package com.dsh.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    /** 桌宠是否被用户「暂时隐藏」（渲染窗口 GONE + 触摸层收回，服务仍常驻） */
    @Volatile
    private var petHidden = false

    /** 点击音效合成器（懒建，销毁时释放；ToneGenerator 反复 new 会泄漏音频资源） */
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // wm 改成按需取（见 windowHost）：完全体模式下要用无障碍服务的 Context
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
        }
        // 走到这里说明本次启动是「用户希望桌宠运行」的（启动按钮 / 通知栏动作 / 保活拉起）：
        // 落盘意图（进程被 ROM 杀掉后靠它决定该不该复活），并挂上 15 分钟看门狗。
        // 用户主动停止时由 SettingsActivity.stopPet 清掉这两样，所以不会变成关不掉的进程。
        KeepAlive.setWanted(this, true)
        KeepAlive.scheduleWatchdog(this)
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
        }
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER

        val assetLoader = buildAssetLoader()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
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
        val mime = mapOf(
            "html" to "text/html", "js" to "text/javascript", "jsonc" to "text/plain",
            "json" to "application/json", "png" to "image/png", "ttf" to "font/ttf",
            "webm" to "video/webm", "gif" to "image/gif", "css" to "text/css",
        )
        fun handler(root: String) = WebViewAssetLoader.PathHandler { path ->
            val rel = path.trimStart('/')
            // assets.open 不接受前导 "/"，root 为空时（=assets 根）只取 rel 本身
            val assetPath = if (root.isEmpty()) rel else "$root/$rel"
            try {
                val stream = assets.open(assetPath)
                val ext = rel.substringAfterLast('.', "").lowercase()
                WebResourceResponse(mime[ext] ?: "application/octet-stream", null, stream)
            } catch (e: Exception) {
                null
            }
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
        ensureChannel(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        val n = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_ID, n)
        }
    }

    /**
     * 常驻通知：标题 + 状态 + 两个动作（显示/隐藏桌宠、设置）。
     *
     * 「暂时隐藏」在渲染层是把 display 改成 none——命中区没了、气泡也没了，宠物就彻底看不见。
     * 手机上没有右键菜单可点、也没有桌面可以去翻，**通知栏就是唯一还摸得到的入口**，
     * 所以这里必须给一个能把它叫回来的按钮，否则用户只能去设置页停服务再开。
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
        val text = if (petHidden) "桌宠已隐藏，点下方按钮恢复" else "桌宠运行中"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(settingsPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, toggleLabel, togglePi).build())
            .addAction(Notification.Action.Builder(null, "设置", settingsPi).build())
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
            if (!hidden) {
                // 恢复后让渲染层重新上报命中区（隐藏期间它上报的都被丢掉了）
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
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun nextNotifyId(): Int = (System.currentTimeMillis() % 100000).toInt()

    override fun onDestroy() {
        super.onDestroy()
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