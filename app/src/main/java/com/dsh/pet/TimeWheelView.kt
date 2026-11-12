package com.dsh.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 六列 3D 圆柱滚轮（年 / 月 / 日 / 时 / 分 / 秒）。
 *
 * 交互（需求 1）：跟手滚动 · 松手惯性 · 减速吸附 · 3D 圆柱弯曲 · 月日联动。
 * 性能（v1.3.2 丝滑优化）：onDraw 内颜色查一次缓存（原来每行每列都查资源，是卡顿主因）；
 * 每行预计算缩放/透明度/字号，避免重复 cos/资源查找。
 * 暗黑适配：全部颜色走 R.color（values / values-night 双份）。
 */
class TimeWheelView(context: Context) : View(context) {

    companion object {
        private const val VIS_ROWS = 5f
        private const val FRICTION = 0.94f
        private const val SNAP_MS = 280L
        private const val COLUMNS = 6
        /** 单次松手惯性最多滑动的格数（轻滑 1~2 格，快甩才接近此上限，避免「轻点飞几十格」）。 */
        private const val MAX_FLING = 6
    }

    private class Column(
        val min: Int,
        var max: Int,
        private val fmt: (Int) -> String,
    ) {
        var value = 0f
        var velocity = 0f          // 行/毫秒（松手实测速度）
        var dragging = false
        var lastY = 0f
        var lastMoveValue = 0f     // 最近一次 MOVE 的 value（松手前，用于算速度）
        var lastMoveTime = 0L      // 最近一次 MOVE 的时刻
        var speedWindowMs = 0L     // 最近一次与倒数第二次的间隔（估算速度分母）
        var prevMoveValue = 0f
        var prevMoveTime = 0L

        fun sel(): Int = Math.round(value).toInt().coerceIn(min, max)
        fun text(v: Int): String = fmt(v)
        fun clamp() {
            if (value > max) value = max.toFloat()
            if (value < min) value = min.toFloat()
        }
    }

    private val now = LocalDate.now()
    private val nowT = java.time.LocalTime.now()

    private val columns = Array(COLUMNS) { i ->
        when (i) {
            0 -> Column(2024, 2035, { "${it}" }).also { c -> c.value = now.year.toFloat() }
            1 -> Column(1, 12, { "%02d".format(it) }).also { c -> c.value = now.monthValue.toFloat() }
            2 -> Column(1, YearMonth.of(now.year, now.monthValue).lengthOfMonth(),
                { "%02d".format(it) }).also { c -> c.value = now.dayOfMonth.toFloat() }
            3 -> Column(0, 23, { "%02d".format(it) }).also { c -> c.value = nowT.hour.toFloat() }
            4 -> Column(0, 59, { "%02d".format(it) }).also { c -> c.value = nowT.minute.toFloat() }
            else -> Column(0, 59, { "%02d".format(it) }).also { c -> c.value = nowT.second.toFloat() }
        }
    }

    // 列头标签（年 月 日 时 分 秒）：只在顶部标签区画一次
    private val colLabels = arrayOf("年", "月", "日", "时", "分", "秒")

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var colWidth = 0f
    private var rowHeight = 0f
    private var downCol = -1
    private var dragThresholdPx = 0f
    private val snapInterp = DecelerateInterpolator()
    private var snapDoing = false
    private var snapFrom = 0f
    private var snapTo = 0f
    private var snapStart = 0L
    private var snapFrameCallback: Choreographer.FrameCallback? = null

    // 颜色缓存：每帧 onDraw 开头取一次（避免每行重复查资源——卡顿主因）
    private var cPrimary = 0
    private var cHint = 0
    private var cAccent = 0
    private var cStroke = 0
    // 跨格「咔哒」：记录每列上一次已响过的 sel，跟手/吸附每跨一格播一次 tick
    private val lastNotifiedSel = IntArray(COLUMNS) { Int.MIN_VALUE }
    // 自产 tick 音源（SoundPool + res/raw/tick.wav，走媒体流，不受系统「系统音效」开关影响）
    private var soundPool: SoundPool? = null
    private var tickSoundId = 0

    /** 播一次咔哒（音源未就绪时静默跳过，不崩）。 */
    private fun playTick() {
        val p = soundPool ?: return
        if (tickSoundId == 0) return
        p.play(tickSoundId, 0.6f, 0.6f, 1, 0, 1.0f)
    }

    init {
        val d = resources.displayMetrics.density
        dragThresholdPx = 8f * d
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.dsh_wheel_bg))
            cornerRadius = 14f * d
        }
        setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
        // 初始化自产 tick 音源（SoundPool，走媒体流；不受系统「系统音效」开关影响）
        val sp = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        soundPool = sp
        sp.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) tickSoundId = id
        }
        sp.load(context, R.raw.tick, 1)
        post { invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        colWidth = (w - paddingLeft - paddingRight).toFloat() / COLUMNS
        rowHeight = ((h - paddingTop - paddingBottom) / VIS_ROWS).coerceAtLeast(28f * resources.displayMetrics.density / 2f)
    }

    fun picked(): Instant {
        val y = columns[0].sel()
        val m = columns[1].sel()
        val day = columns[2].sel().coerceIn(1, daysInMonth(y, m))
        val h = columns[3].sel()
        val mi = columns[4].sel()
        val s = columns[5].sel()
        return LocalDate.of(y, m, day).atTime(h, mi, s).atZone(ZoneId.systemDefault()).toInstant()
    }

    private fun daysInMonth(year: Int, month: Int): Int =
        try { YearMonth.of(year, month).lengthOfMonth() } catch (e: Exception) { 30 }

    private fun onMonthChanged() {
        val y = columns[0].sel()
        val m = columns[1].sel()
        columns[2].max = daysInMonth(y, m)
        columns[2].clamp()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 每帧颜色查一次（暗黑切换时也能自动跟新）
        cPrimary = ContextCompat.getColor(context, R.color.dsh_text_primary)
        cHint = ContextCompat.getColor(context, R.color.dsh_text_hint)
        cAccent = ContextCompat.getColor(context, R.color.dsh_accent)
        cStroke = ContextCompat.getColor(context, R.color.dsh_accent_stroke)

        val padL = paddingLeft
        val top = paddingTop
        val centerY = top + (VIS_ROWS - 1f) / 2f * rowHeight + rowHeight / 2f

        for (i in columns.indices) {
            val col = columns[i]
            val left = padL + i * colWidth
            // 中缝分隔线
            if (i > 0) {
                colDividerPaint.color = cStroke
                colDividerPaint.alpha = 90
                colDividerPaint.strokeWidth = 1f
                canvas.drawLine(left, top.toFloat(), left, top + VIS_ROWS * rowHeight, colDividerPaint)
            }
            // 选中行高亮条
            highlightPaint.color = cAccent
            highlightPaint.alpha = 28
            val selRowTop = top + rowHeight * (VIS_ROWS - 1f) / 2f
            canvas.drawRoundRect(left + 4f, selRowTop, left + colWidth - 4f, selRowTop + rowHeight, 8f, 8f, highlightPaint)

            val sel = col.sel()
            // 跟手/吸附时该列每跨一格「咔哒」一次（系统内置 tick，无需音频资源）
            if (i == downCol && (col.dragging || snapDoing) && sel != lastNotifiedSel[i]) {
                lastNotifiedSel[i] = sel
                playTick()
            }
            for (s in (sel - 2)..(sel + 2)) {
                if (s < col.min || s > col.max) continue
                val relRow = (s - col.value)
                var y = top + ((VIS_ROWS - 1f) / 2f + relRow) * rowHeight
                // 3D 圆柱弯曲：离中心越远越窄 + 上移 + 变小
                val curve = Math.cos(relRow * Math.PI / VIS_ROWS).toFloat()
                val shrink = 1f - (1f - curve) * 0.16f
                val cy = y + rowHeight / 2f
                val shiftY = cy - centerY
                y = cy + shiftY * shrink * 0.9f

                textPaint.textSize = rowHeight * 0.44f * shrink
                val isSel = s == sel
                textPaint.color = if (isSel) cPrimary else cHint
                textPaint.alpha = if (isSel) 255 else (150 - Math.abs(s - sel) * 45).coerceIn(70, 150).toInt()
                canvas.drawText(col.text(s), left + colWidth / 2f, y + textPaint.textSize * 0.35f, textPaint)
            }

            // 列头标签（顶部居中）
            labelPaint.color = cHint
            labelPaint.alpha = 120
            labelPaint.textSize = rowHeight * 0.30f
            canvas.drawText(colLabels[i], left + colWidth / 2f, top + rowHeight * 0.25f, labelPaint)
        }
    }
    /** 吸附动画：由 Choreographer 独立帧循环驱动（不依赖系统是否调 onDraw，保证一定跑到整数格）。 */
    private fun stepSnap() {
        val down = downCol
        if (down < 0) { snapDoing = false; return }
        val t = ((System.currentTimeMillis() - snapStart).toFloat() / SNAP_MS).coerceIn(0f, 1f)
        val e = snapInterp.getInterpolation(t)
        val c = columns[down]
        c.value = snapFrom + (snapTo - snapFrom) * e
        if (t < 1f) {
            invalidate()
            Choreographer.getInstance().postFrameCallback(snapFrameCallback!!)
        } else {
            c.value = snapTo  // 定格到整数格，绝不停在两数字之间
            snapDoing = false
            downCol = -1
            snapFrameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
            snapFrameCallback = null
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                snapDoing = false
                downCol = columnAtX(event.x)
                val c = columns[downCol]
                c.dragging = true
                c.lastY = event.y
                c.velocity = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                performClick()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 吸附完成后 downCol 已置 -1，此时系统可能又补发 MOVE（手指再动/多指），直接忽略防越界
                if (downCol < 0) return false
                val c = columns[downCol]
                val dy = event.y - c.lastY
                c.prevMoveValue = c.lastMoveValue
                c.prevMoveTime = c.lastMoveTime
                c.value -= dy / rowHeight
                c.value = c.value.coerceIn(c.min.toFloat(), c.max.toFloat())
                c.lastMoveValue = c.value
                c.lastMoveTime = SystemClock.uptimeMillis()
                c.lastY = event.y
                if (downCol == 1) onMonthChanged()
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 未处于拖拽（吸附已结束 / 该事件非本列）则不启动新的吸附，防 columns[-1] 越界
                if (downCol < 0) return false
                val c = columns[downCol]
                c.dragging = false
                startFlingAndSnap()
                return true
            }
        }
        return false
    }

    private fun columnAtX(x: Float): Int {
        val i = ((x - paddingLeft) / colWidth).toInt()
        return i.coerceIn(0, COLUMNS - 1)
    }

    /** 松手惯性：用最近两次 MOVE 估算真实速度（行/毫秒），按时间常数估滑行距离，
     *  封顶 ±MAX_FLING 格——轻滑只飞 1~2 格，快甩才接近封顶。取代旧版把「每事件行数」
     *  当「每帧」放大约 2000 倍导致「轻轻滚一下从 1 飞到 30」的问题。 */
    private fun startFlingAndSnap() {
        val c = columns[downCol]
        val dtMs = (c.lastMoveTime - c.prevMoveTime).coerceAtLeast(1L)
        // 行/毫秒（带符号；手指上滑 value 减小 → 负）
        var vPerMs = (c.lastMoveValue - c.prevMoveValue) / dtMs.toFloat()
        // 两次间隔太短（连续同帧）速度噪声大，向 0 收敛
        if (dtMs < 8) vPerMs *= 0.3f
        // 惯性滑行总行数 = 速度 × 减速时间常数，封顶 MAX_FLING
        val decayMs = 240f
        var flingRows = (vPerMs * decayMs).toInt()
        if (flingRows > MAX_FLING) flingRows = MAX_FLING
        if (flingRows < -MAX_FLING) flingRows = -MAX_FLING
        var target = (c.value + flingRows).toInt()
        target = target.coerceIn(c.min, c.max)
        // 几乎没动（原地松手）→ 直接吸附到最近整行（咔哒）
        if (Math.round(c.value).toInt() == target) flingRows = 0
        snapFrom = c.value
        snapTo = target.toFloat()
        snapStart = System.currentTimeMillis()
        snapDoing = true
        c.velocity = 0f
        if (downCol == 1) onMonthChanged()
        // Choreographer 独立帧循环驱动吸附（不依赖 onDraw 调度，保证一定定位到整数格）
        snapFrameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        snapFrameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) { stepSnap() }
        }
        Choreographer.getInstance().postFrameCallback(snapFrameCallback!!)
    }

    fun setPicked(y: Int, m: Int, d: Int, h: Int, mi: Int, s: Int) {
        columns[0].value = y.toFloat()
        columns[1].value = m.toFloat()
        columns[2].max = daysInMonth(y, m)
        columns[2].value = d.coerceIn(1, columns[2].max).toFloat()
        columns[3].value = h.toFloat()
        columns[4].value = mi.toFloat()
        columns[5].value = s.toFloat()
        invalidate()
    }
}
