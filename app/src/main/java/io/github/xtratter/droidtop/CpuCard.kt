package io.github.xtratter.droidtop

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.annotation.SuppressLint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/** Карточка процессора: большая цифра загрузки, температура и столбики-«пилюли» ядер. */
class CpuCard(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private fun dp(v: Float) = Ui.dp(context, v)
    private val pad = dp(20f)
    private val labelP = Ui.textPaint(ctx, 13f, Ui.medium, Ui.TEXT2)
    private val bigP = Ui.textPaint(ctx, 44f, Ui.bold)
    private val unitP = Ui.textPaint(ctx, 20f, Ui.medium, Ui.TEXT2)
    private val smallP = Ui.textPaint(ctx, 12f, Ui.regular, Ui.TEXT2)
    private val freqP = Ui.textPaint(ctx, 10.5f, Ui.medium, Ui.TEXT3).apply { textAlign = Paint.Align.CENTER }
    private val chipP = Ui.textPaint(ctx, 13f, Ui.medium)
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TRACK }
    private val r = RectF()
    private val barsH = dp(70f)
    private val smooth = Smooth(this)
    private var barShader: Shader? = null
    private var s: Snapshot? = null
    private val chart = Chart(this)
    private val chartH = dp(64f)

    init {
        background = GlassDrawable(ctx, 28f)
    }

    fun update(snap: Snapshot) {
        s = snap
        // [0] — общая загрузка (или частота без root), дальше — по ядру
        val v = FloatArray(snap.cores.size + 1)
        v[0] = if (!snap.cpu.isNaN()) snap.cpu else (snap.cores.maxOfOrNull { it.freqKHz } ?: 0L).toFloat()
        snap.cores.forEachIndexed { i, c -> v[i + 1] = frac(c) }
        smooth.set(v)
        chart.values = History.cpu.toArray()
        chart.color = Ui.primary
        chart.format = if (snap.cpu.isNaN()) { x -> Fmt.pct(x) + "% " + context.getString(R.string.ch_of_max) } else { x -> Fmt.pct(x) + "%" }
    }

    private fun frac(c: CoreInfo) = when {
        !c.usage.isNaN() -> c.usage / 100f
        c.maxKHz > 0 && c.freqKHz > 0 -> c.freqKHz.toFloat() / c.maxKHz
        else -> 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = pad * 2 + dp(18f) + dp(56f) + dp(20f) + dp(22f) + chartH + dp(14f) + barsH + dp(22f)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val top = h - pad - dp(22f) - barsH
        barShader = LinearGradient(0f, top + barsH, 0f, top, Ui.primary, Ui.mix(Ui.primary, 0xFFFFFFFF.toInt(), 0.45f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val snap = s ?: return
        val v = smooth.cur
        if (v.isEmpty()) return
        val res = context
        var y = pad - labelP.ascent()
        c.drawText(res.getString(R.string.c_cpu), pad, y, labelP)

        // большая цифра
        y += dp(52f)
        val rootLoad = !snap.cpu.isNaN()
        val big: String
        val unit: String
        if (rootLoad) {
            big = String.format(Locale.getDefault(), "%.0f", v[0].coerceIn(0f, 100f)); unit = "%"
            bigP.color = Ui.load(v[0])
        } else {
            big = String.format(Locale.getDefault(), "%.2f", v[0] / 1e6f); unit = res.getString(R.string.u_ghz)
            bigP.color = Ui.TEXT
        }
        c.drawText(big, pad, y, bigP)
        c.drawText(unit, pad + bigP.measureText(big) + dp(4f), y, unitP)

        // чипы справа: температура и число ядер
        var cx = width - pad
        val t = snap.cpuTemp
        if (!t.isNaN()) cx = chip(c, cx, y - dp(34f), Fmt.temp(t), Ui.load(t, 60f, 80f))
        val maxF = snap.cores.maxOfOrNull { it.maxKHz } ?: 0L
        val sub = res.resources.getQuantityString(R.plurals.c_cores, snap.cores.size, snap.cores.size) +
            (if (maxF > 0) " · " + res.getString(R.string.c_up_to, Human.ghz(res, maxF)) else "")
        smallP.textAlign = Paint.Align.RIGHT
        c.drawText(sub, width - pad, y, smallP)
        smallP.textAlign = Paint.Align.LEFT
        if (!rootLoad) {
            y += dp(20f)
            c.drawText(res.getString(R.string.c_freq_only), pad, y, smallP)
        }

        // история загрузки
        val top = height - pad - dp(22f) - barsH
        chart.rect.set(pad, top - dp(14f) - chartH, width - pad, top - dp(14f))
        chart.draw(c)

        // столбики ядер
        val n = snap.cores.size
        val gap = if (n > 12) dp(4f) else dp(8f)
        val bw = (width - 2 * pad - gap * (n - 1)) / n
        val rad = minOf(bw / 2, dp(12f))
        for (i in 0 until n) {
            val left = pad + i * (bw + gap)
            val core = snap.cores[i]
            fillP.shader = null
            when {
                !core.usage.isNaN() && core.usage >= 85f -> fillP.color = Ui.HOT
                core.usage.isNaN() -> fillP.color = Ui.withAlpha(Ui.primary, 0.75f)
                // непрозрачный цвет: его альфа умножается на градиент, а после «пилюли» температуры там 18 %
                else -> { fillP.color = 0xFF000000.toInt(); fillP.shader = barShader }
            }
            PillBar.draw(c, left, top, left + bw, top + barsH, rad, v.getOrElse(i + 1) { 0f }, trackP, fillP)
            val fr = snap.cores[i].freqKHz
            c.drawText(if (fr > 0) String.format(Locale.getDefault(), "%.1f", fr / 1e6) else "—",
                left + bw / 2, top + barsH + dp(16f), freqP)
        }
    }

    /** Маленькая «пилюля» с текстом, выровненная по правому краю; возвращает её левую границу. */
    private fun chip(c: Canvas, right: Float, top: Float, text: String, color: Int): Float {
        val w = chipP.measureText(text) + dp(20f)
        r.set(right - w, top, right, top + dp(28f))
        fillP.shader = null
        fillP.color = Ui.withAlpha(color, 0.18f)
        c.drawRoundRect(r, dp(14f), dp(14f), fillP)
        chipP.color = color
        c.drawText(text, r.left + dp(10f), r.centerY() - (chipP.ascent() + chipP.descent()) / 2, chipP)
        return r.left - dp(6f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent) = chart.onTouch(e) || super.onTouchEvent(e)
}
