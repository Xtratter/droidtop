package io.github.xtratter.droidtop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * График истории внутри карточки: одна серия, линия 2dp и лёгкая заливка под ней,
 * сетка — тонкие линии на 0, ½ и максимуме. Палец на графике — вертикаль и значение в этой точке.
 */
class Chart(private val view: View) {
    private val ctx = view.context
    private fun dp(v: Float) = Ui.dp(ctx, v)

    val rect = RectF()
    var values = FloatArray(0)
        set(v) { field = v; dirty = true }
    /** Верх шкалы; при [autoMax] — не меньше этого значения. */
    var maxY = 100f
    var autoMax = false
    var color = Ui.primary
    var format: (Float) -> String = { Fmt.pct(it) + "%" }
    /** Сколько точек может быть в серии — окно не шире этого. */
    var capacity = History.SIZE
    private val shown get() = span.coerceAtMost(capacity)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = 0x1FFFFFFF; strokeWidth = 1f }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt(); strokeWidth = dp(1f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisP = Ui.textPaint(ctx, 10f, Ui.regular, Ui.TEXT3)
    private val tipP = Ui.textPaint(ctx, 12f, Ui.medium, Ui.TEXT)
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0262930.toInt() }
    private val tipRect = RectF()
    private val path = Path()
    private val fill = Path()
    private var shaderFor = 0f
    private var touchX = -1f
    private var pinchDist = 0f
    private var pinchSpan = 0

    // Готовая картинка графика: перерисовываем её только при новых данных, а кадры анимации
    // карточки (числа, столбики) просто кладут её на место — линия в 600 точек не строится заново.
    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private var dirty = true
    private val cacheRect = RectF()
    private var cacheSpan = 0
    private var cacheColor = 0

    companion object {
        /** Сколько последних точек видно на графиках; общий для всех, меняется щипком. */
        var span = 150
        const val MIN_SPAN = 20
    }

    fun draw(c: Canvas) {
        // запас вокруг области графика: сверху подписи шкалы, по краям точка «сейчас»
        val side = dp(8f)
        val above = dp(24f)
        val w = (rect.width() + 2 * side).toInt()
        val h = (rect.height() + above + side).toInt()
        if (w <= 0 || h <= 0) return
        var b = cache
        if (b == null || b.width != w || b.height != h) {
            b?.recycle()
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cache = b; cacheCanvas = Canvas(b); dirty = true
        }
        if (dirty || cacheRect != rect || cacheSpan != span || cacheColor != color) {
            dirty = false; cacheRect.set(rect); cacheSpan = span; cacheColor = color
            b.eraseColor(0)
            val cc = cacheCanvas!!
            cc.save()
            cc.translate(side - rect.left, above - rect.top)
            drawStatic(cc)
            cc.restore()
        }
        c.drawBitmap(b, rect.left - side, rect.top - above, null)
        if (touchX >= 0 && pinchDist == 0f) drawTouch(c)
    }

    private fun drawStatic(c: Canvas) {
        // видимое окно — последние span точек
        val all = values.size
        val from = (all - shown).coerceAtLeast(0)
        val n = all - from
        val h = rect.height()
        val top = maxY(from)
        for (f in floatArrayOf(0f, 0.5f, 1f)) {
            val y = rect.bottom - h * f
            c.drawLine(rect.left, y, rect.right, y, grid)
        }
        // подписи шкалы — приглушённым текстом, не цветом серии
        axisP.textAlign = Paint.Align.RIGHT
        c.drawText(format(top), rect.right, rect.top - dp(4f), axisP)
        axisP.textAlign = Paint.Align.LEFT
        val seconds = shown * History.secondsPerPoint
        c.drawText(if (seconds < 90f) ctx.getString(R.string.ch_span_s, seconds.roundToInt())
            else ctx.getString(R.string.ch_span, (seconds / 60f).roundToInt()), rect.left, rect.top - dp(4f), axisP)

        if (n < 2) {
            axisP.textAlign = Paint.Align.CENTER
            c.drawText(ctx.getString(R.string.ch_collecting), rect.centerX(), rect.centerY(), axisP)
            return
        }
        val step = rect.width() / (shown - 1)
        fun x(i: Int) = rect.right - (n - 1 - i) * step
        fun y(v: Float) = rect.bottom - h * (v / top).coerceIn(0f, 1f)
        fun v(i: Int) = values[from + i]

        path.reset(); fill.reset()
        path.moveTo(x(0), y(v(0)))
        for (i in 1 until n) path.lineTo(x(i), y(v(i)))
        fill.set(path)
        fill.lineTo(x(n - 1), rect.bottom); fill.lineTo(x(0), rect.bottom); fill.close()
        if (shaderFor != rect.top + color) {
            shaderFor = rect.top + color
            area.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                Ui.withAlpha(color, 0.22f), Ui.withAlpha(color, 0.02f), Shader.TileMode.CLAMP)
        }
        c.drawPath(fill, area)
        line.color = color
        c.drawPath(path, line)

        // точка «сейчас»
        dot.color = Ui.base; c.drawCircle(x(n - 1), y(v(n - 1)), dp(5f), dot)
        dot.color = color; c.drawCircle(x(n - 1), y(v(n - 1)), dp(3.5f), dot)
    }

    /** Вертикаль и подсказка под пальцем — поверх готовой картинки. */
    private fun drawTouch(c: Canvas) {
        val all = values.size
        val from = (all - shown).coerceAtLeast(0)
        val n = all - from
        if (n < 2) return
        val h = rect.height()
        val top = maxY(from)
        val step = rect.width() / (shown - 1)
        fun x(i: Int) = rect.right - (n - 1 - i) * step
        fun y(v: Float) = rect.bottom - h * (v / top).coerceIn(0f, 1f)
        fun v(i: Int) = values[from + i]

        val i = (n - 1 - ((rect.right - touchX) / step).roundToInt()).coerceIn(0, n - 1)
        val px = x(i)
        val py = y(v(i))
        c.drawLine(px, rect.top, px, rect.bottom, cross)
        dot.color = Ui.base; c.drawCircle(px, py, dp(6f), dot)
        dot.color = color; c.drawCircle(px, py, dp(4f), dot)
        val ago = ((n - 1 - i) * History.secondsPerPoint).roundToInt()
        val tip = format(v(i)) + " · " +
            if (ago == 0) ctx.getString(R.string.ch_now) else ctx.getString(R.string.ch_ago, Human.ago(ctx, ago))
        val tw = tipP.measureText(tip) + dp(20f)
        val th = dp(28f)
        val left = (px - tw / 2).coerceIn(rect.left, rect.right - tw)
        val tTop = (py - th - dp(10f)).let { if (it < rect.top - th) py + dp(10f) else it }
        tipRect.set(left, tTop, left + tw, tTop + th)
        c.drawRoundRect(tipRect, th / 2, th / 2, tipBg)
        c.drawText(tip, left + dp(10f), tipRect.centerY() - (tipP.ascent() + tipP.descent()) / 2, tipP)
    }

    private fun maxY(from: Int): Float {
        if (!autoMax) return maxY
        var m = maxY
        for (i in from until values.size) if (values[i] * 1.15f > m) m = values[i] * 1.15f
        return niceCeil(m)
    }

    private fun niceCeil(v: Float): Float {
        val steps = floatArrayOf(0.5f, 1f, 2f, 3f, 5f, 8f, 10f, 15f, 20f, 30f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f)
        return steps.firstOrNull { it >= v } ?: v
    }

    /** Касание: true — событие наше (палец на графике). Два пальца — зум по времени. */
    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (touchX < 0 || e.pointerCount != 2) return touchX >= 0
                pinchDist = abs(e.getX(0) - e.getX(1)).coerceAtLeast(dp(16f))
                pinchSpan = span
            }
            MotionEvent.ACTION_POINTER_UP -> if (touchX < 0) return false else pinchDist = 0f
            MotionEvent.ACTION_DOWN -> {
                if (!rect.contains(e.x, e.y) && !(e.x in rect.left..rect.right && e.y in rect.top - dp(16f)..rect.bottom + dp(16f)))
                    return false
                view.parent?.requestDisallowInterceptTouchEvent(true)
                touchX = e.x.coerceIn(rect.left, rect.right)
            }
            MotionEvent.ACTION_MOVE -> when {
                touchX < 0 -> return false
                pinchDist > 0f && e.pointerCount >= 2 -> {
                    // пальцы разводим — окно короче (крупнее), сводим — длиннее
                    val d = abs(e.getX(0) - e.getX(1)).coerceAtLeast(dp(16f))
                    span = (pinchSpan * pinchDist / d).roundToInt().coerceIn(MIN_SPAN, History.SIZE)
                }
                pinchDist == 0f -> touchX = e.x.coerceIn(rect.left, rect.right)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchX < 0) return false
                touchX = -1f
                pinchDist = 0f
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        view.invalidate()
        return true
    }
}
