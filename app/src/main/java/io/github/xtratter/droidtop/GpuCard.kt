package io.github.xtratter.droidtop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/** Карточка видеочипа: загрузка крупно, частота, температура и график загрузки. Скрыта, если данных нет. */
class GpuCard(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private fun dp(v: Float) = Ui.dp(context, v)
    private val pad = dp(20f)
    private val labelP = Ui.textPaint(ctx, 13f, Ui.medium, Ui.TEXT2)
    private val bigP = Ui.textPaint(ctx, 28f, Ui.bold)
    private val unitP = Ui.textPaint(ctx, 15f, Ui.medium, Ui.TEXT2)
    private val smallP = Ui.textPaint(ctx, 12f, Ui.regular, Ui.TEXT2)
    private val chipP = Ui.textPaint(ctx, 12f, Ui.medium)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TRACK }
    private val r = RectF()
    private val smooth = Smooth(this)
    private val chart = Chart(this).apply { color = Ui.secondary }
    private val chartH = dp(56f)
    private var g: Gpu? = null
    private var hasBusy = true

    init {
        background = GlassDrawable(ctx, 28f)
    }

    fun update(s: Snapshot) {
        val gpu = s.gpu
        g = gpu
        visibility = if (gpu == null) GONE else VISIBLE
        if (gpu == null) return
        val busy = !gpu.busy.isNaN()
        if (busy != hasBusy) { hasBusy = busy; requestLayout() }
        smooth.set(floatArrayOf(gpu.fraction))
        chart.values = History.gpu.toArray()
        chart.format = if (busy) { x -> Fmt.pct(x) + "%" } else { x -> Fmt.pct(x) + "% " + context.getString(R.string.ch_of_max) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var h = pad * 2 + dp(18f) + dp(38f) + dp(12f) + dp(8f) + dp(26f) + chartH
        if (!hasBusy) h += dp(20f)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onDraw(c: Canvas) {
        val gpu = g ?: return
        val ctx = context
        val v = smooth.cur.getOrElse(0) { 0f }.coerceIn(0f, 1f)
        var y = pad - labelP.ascent()
        c.drawText(gpu.model?.let { ctx.getString(R.string.c_gpu_model, it) } ?: ctx.getString(R.string.c_gpu), pad, y, labelP)

        // температура — «пилюля» справа
        if (!gpu.temp.isNaN()) {
            val t = Fmt.temp(gpu.temp)
            val color = Ui.load(gpu.temp, 60f, 80f)
            val cw = chipP.measureText(t) + dp(20f)
            r.set(width - pad - cw, y - dp(18f), width - pad, y + dp(6f))
            p.color = Ui.withAlpha(color, 0.18f)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
            chipP.color = color
            c.drawText(t, r.left + dp(10f), r.centerY() - (chipP.ascent() + chipP.descent()) / 2, chipP)
        }

        // загрузка крупно (или частота, если загрузки нет), частота справа
        y += dp(40f)
        val big: String
        val unit: String
        if (hasBusy) {
            big = String.format(Locale.getDefault(), "%.0f", v * 100); unit = "%"
            bigP.color = Ui.load(v * 100)
        } else {
            big = if (gpu.freqMHz > 0) gpu.freqMHz.toString() else "—"; unit = ctx.getString(R.string.u_mhz)
            bigP.color = Ui.TEXT
        }
        c.drawText(big, pad, y, bigP)
        c.drawText(" $unit", pad + bigP.measureText(big), y, unitP)
        if (gpu.freqMHz > 0) {
            val f = if (gpu.maxMHz > 0) ctx.getString(R.string.c_gpu_freq, gpu.freqMHz, gpu.maxMHz)
            else "${gpu.freqMHz} " + ctx.getString(R.string.u_mhz)
            smallP.textAlign = Paint.Align.RIGHT
            c.drawText(f, width - pad, y, smallP)
            smallP.textAlign = Paint.Align.LEFT
        }

        // полоса загрузки
        y += dp(12f)
        val bh = dp(8f)
        r.set(pad, y, width - pad, y + bh)
        c.drawRoundRect(r, bh / 2, bh / 2, trackP)
        if (v > 0.01f) {
            r.right = pad + maxOf((width - 2 * pad) * v, bh)
            p.color = if (hasBusy && v >= 0.85f) Ui.HOT else Ui.secondary
            c.drawRoundRect(r, bh / 2, bh / 2, p)
        }
        y += bh

        if (!hasBusy) {
            y += dp(20f)
            c.drawText(ctx.getString(R.string.c_gpu_freq_only), pad, y, smallP)
        }

        chart.rect.set(pad, y + dp(26f), width - pad, y + dp(26f) + chartH)
        chart.draw(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent) = chart.onTouch(e) || super.onTouchEvent(e)
}
