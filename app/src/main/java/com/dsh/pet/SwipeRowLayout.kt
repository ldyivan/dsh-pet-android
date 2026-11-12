package com.dsh.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * 微信式「左滑露出操作」行容器。
 *
 * 为什么用自定义 ViewGroup 而不是给卡片挂 OnTouchListener：
 * 卡片（LinearLayout）不可点击，`setOnTouchListener` 的 ACTION_DOWN 返回 false 时
 * 该 View 不会成为 touch target，后续 MOVE/UP **根本不会派发给它** ——
 * 于是滑动判定永不成立（滑不动），DOWN 时排队的长按计时也没机会被 MOVE 取消（误弹编辑窗）。
 *
 * 手势策略：
 *  · DOWN 不拦截 —— 子元素（勾选框/按钮/文本）先正常拿事件，点击/勾选照旧；
 *  · MOVE 判「水平位移明显且大于纵向」才 intercept 接管 —— 垂直滚动仍交还父 ScrollView；
 *  · 接管后增量跟手 translationX（抗丢帧、不抖），松手按半程吸附；
 *  · 长按在 dispatchTouchEvent 统一计时（按住不动 480ms 才回调），任何位移 >6dp 立即取消，
 *    与滑动完全解耦 —— 滑动（哪怕慢滑）绝不会误触长按。
 *
 * 删除按钮（actionView）：
 *  · 平时完整停在内容右侧之外（translationX = revealWidth，被 clipChildren 裁掉），
 *    随内容一起往左滑入 —— 真正的「滑出」动画，不是原地淡入；
 *  · 收起时先滑回右侧屏外、动画结束再置 GONE（**不能只把 alpha 归零**，
 *    否则会变成「看不见但还能点」的幽灵按钮）；
 *  · 打开某一行时自动收起其它行（同行互斥）。
 */
class SwipeRowLayout(context: Context) : FrameLayout(context) {

    companion object {
        /** 当前处于「已展开」状态的行：打开新行时自动收起它（互斥）。 */
        private var openRow: SwipeRowLayout? = null
    }

    /** 删除按钮露出的宽度（px）。 */
    var revealWidth: Int = 0
    /** 点「删除」回调。 */
    var onDelete: (() -> Unit)? = null
    /** 长按回调（弹编辑窗）。 */
    var onLongPress: (() -> Unit)? = null
    /** 首次滑动（或长按）时回调一次，用于隐藏引导提示。 */
    var onInteract: (() -> Unit)? = null

    private var contentView: View? = null
    private var actionView: View? = null
    private val main = Handler(Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    /** 已接管本次手势（判定为水平拖动）。 */
    private var intercepting = false

    private val slop = 12f * resources.displayMetrics.density
    private val minSlop = 6f * resources.displayMetrics.density

    private val longPressTask = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            onLongPress?.invoke()
        }
    }

    /** 绑定内容层（卡片）与操作层（删除按钮）。 */
    fun bind(content: View, action: View, reveal: Int) {
        contentView = content
        actionView = action
        revealWidth = reveal
        removeAllViews()
        clipChildren = true
        // 操作层贴在行的右边缘，但初始整体平移到行右缘之外（屏外），滑动时再滑入。
        // 高度用 WRAP_CONTENT + 垂直居中：若用 MATCH_PARENT，在 FrameLayout 的 WRAP 父约束下
        // 会按「可用屏高」测量，把整行撑成屏幕高度 —— 必须避开这个测量陷阱。
        addView(action, LayoutParams(reveal, LayoutParams.WRAP_CONTENT, Gravity.END))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        action.visibility = View.GONE
        action.translationX = reveal.toFloat()
    }

    /**
     * 测量：先按自然尺寸量内容层拿高度，再以「精确 = 内容高度」量操作层。
     * 这样操作按钮高度严格跟随列表项高度；也避开 FrameLayout 在 WRAP 父约束下
     * 把 MATCH_PARENT 子 View 按「可用屏高」测量的陷阱（否则整行会被撑成屏幕高）。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val c = contentView
        val a = actionView
        if (c == null || a == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // 内容层按自然高度测量
        c.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val contentH = c.measuredHeight
        if (contentH > 0) {
            // 注意：不能走 measureChild —— 它内部的 getChildMeasureSpec 会因为子 View 的
            // LayoutParams.height = WRAP_CONTENT 而把 EXACTLY 降级成 AT_MOST，
            // 精确高度根本不生效（这正是「按钮高度不跟随卡片」的根因）。直接 measure 绕开。
            val aw = (a.layoutParams as? LayoutParams)?.width ?: LayoutParams.WRAP_CONTENT
            val actionW = if (aw > 0) aw else revealWidth
            a.measure(
                MeasureSpec.makeMeasureSpec(actionW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY),
            )
        } else {
            a.measure(widthMeasureSpec, heightMeasureSpec)
        }
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (contentH > 0) contentH else a.measuredHeight
        setMeasuredDimension(w, h)
    }

    /** 收拢：内容滑回 0、操作层滑回右侧屏外，动画结束后置 GONE（杜绝幽灵点击区）。 */
    fun closeSwipe() {
        val c = contentView ?: return
        val a = actionView ?: return
        if (openRow === this) openRow = null
        c.animate().translationX(0f).setDuration(150).start()
        if (a.visibility == View.VISIBLE) {
            a.animate().translationX(revealWidth.toFloat()).setDuration(150)
                .withEndAction {
                    if ((contentView?.translationX ?: 0f) == 0f) a.visibility = View.GONE
                }
                .start()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 长按计时统一在这里管：任何位移超阈值立即取消，滑动绝不会误触长按
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                intercepting = false
                main.removeCallbacks(longPressTask)
                main.postDelayed(longPressTask, 480L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(ev.x - downX) > minSlop || Math.abs(ev.y - downY) > minSlop) {
                    main.removeCallbacks(longPressTask)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPressTask)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                intercepting = false
                return false // 不拦截，让子元素先处理点击/勾选
            }
            MotionEvent.ACTION_MOVE -> {
                if (!intercepting) {
                    val dx = Math.abs(ev.x - downX)
                    val dy = Math.abs(ev.y - downY)
                    if (dx > slop && dx > dy * 1.2f) {
                        intercepting = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        beginSwipe()
                    }
                }
                return intercepting
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 仅当没有任何子元素消费 DOWN 时才会走到这里（空白区）
                downX = ev.x; downY = ev.y; lastX = ev.x
                intercepting = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!intercepting) {
                    val dx = Math.abs(ev.x - downX)
                    val dy = Math.abs(ev.y - downY)
                    if (dx > slop && dx > dy * 1.2f) {
                        intercepting = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        beginSwipe()
                    }
                }
                if (intercepting) {
                    val delta = ev.x - lastX
                    lastX = ev.x
                    moveBy(delta)
                    return true
                }
                lastX = ev.x
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (intercepting) {
                    snap()
                    intercepting = false
                    return true
                }
                // 轻点：若已展开则收拢
                if ((contentView?.translationX ?: 0f) != 0f) closeSwipe()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 开始横向拖动：互斥收起其它行，并把操作层显示出来（紧跟内容位移）。 */
    private fun beginSwipe() {
        onInteract?.invoke()
        val other = openRow
        if (other != null && other !== this) other.closeSwipe()
        openRow = this
        val a = actionView ?: return
        if (a.visibility != View.VISIBLE) {
            a.visibility = View.VISIBLE
            // 初始仍在屏外，跟随内容位移滑入（不做淡入，保证「滑出」动画）
            a.translationX = revealWidth + (contentView?.translationX ?: 0f)
        }
    }

    /** 跟手位移：内容 translationX += delta，操作层同步跟随（= revealWidth + 内容位移）。 */
    private fun moveBy(delta: Float) {
        val c = contentView ?: return
        val a = actionView ?: return
        val next = (c.translationX + delta).coerceIn(-revealWidth.toFloat(), 0f)
        c.translationX = next
        a.translationX = revealWidth + next
    }

    /** 松手吸附：超过半程则展开，否则收起。 */
    private fun snap() {
        val c = contentView ?: return
        if (c.translationX < -revealWidth / 2f) {
            val a = actionView ?: return
            a.visibility = View.VISIBLE
            c.animate().translationX(-revealWidth.toFloat()).setDuration(140).start()
            a.animate().translationX(0f).setDuration(140).start()
            openRow = this
        } else {
            closeSwipe()
        }
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(longPressTask)
        if (openRow === this) openRow = null
        super.onDetachedFromWindow()
    }
}