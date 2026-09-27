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

/** Карточка памяти: занято / всего, толстая полоса (занято + кэш) и подкачка. */
class MemCard(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private fun dp(v: Float) = Ui.dp(context, v)
    private val pad = dp(20f)
    private val labelP = Ui.textPaint(ctx, 13f, Ui.medium, Ui.TEXT2)
    private val bigP = Ui.textPaint(ctx, 28f, Ui.bold)
    private val ofP = Ui.textPaint(ctx, 15f, Ui.medium, Ui.TEXT2)
    private val pctP = Ui.textPaint(ctx, 18f, Ui.bold).apply { textAlign = Paint.Align.RIGHT }
    private val smallP = Ui.textPaint(ctx, 12f, Ui.regular, Ui.TEXT2)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val smooth = Smooth(this)
    private var usedShader: Shader? = null
    private var s: Snapshot? = null
    private val chart = Chart(this).apply { color = Ui.tertiary }
    private val chartH = dp(48f)

    init {
        background = GlassDrawable(ctx, 28f)
    }

    fun update(snap: Snapshot) {
        val swapBefore = hasSwap
        s = snap
        if (hasSwap != swapBefore) requestLayout()
        val t = snap.memTotal.coerceAtLeast(1).toFloat()
        val used = (snap.memTotal - snap.memAvail) / t
        val cache = (snap.memAvail - snap.memFree).coerceAtLeast(0) / t
        val swap = if (snap.swapTotal > 0) (snap.swapTotal - snap.swapFree).toFloat() / snap.swapTotal else 0f
        smooth.set(floatArrayOf(used, cache, swap))
        chart.values = History.mem.toArray()
    }

    private val hasSwap get() = (s?.swapTotal ?: 0) > 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var h = pad * 2 + dp(18f) + dp(38f) + dp(14f) + dp(16f) + dp(18f) + dp(26f) + chartH
        if (hasSwap) h += dp(40f)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        usedShader = LinearGradient(pad, 0f, w - pad, 0f, Ui.primary, Ui.tertiary, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val snap = s ?: return
        val v = smooth.cur
        if (v.size < 3) return
        val ctx = context
        var y = pad - labelP.ascent()
        c.drawText(ctx.getString(R.string.c_mem), pad, y, labelP)

        y += dp(40f)
        val usedText = Human.size(ctx, snap.memTotal - snap.memAvail)
        c.drawText(usedText, pad, y, bigP)
        c.drawText(" " + ctx.getString(R.string.c_of, Human.size(ctx, snap.memTotal)),
            pad + bigP.measureText(usedText), y, ofP)
        val pct = v[0] * 100
        pctP.color = Ui.load(pct, 75f, 90f)
        c.drawText(String.format(Locale.getDefault(), "%.0f%%", pct.coerceIn(0f, 100f)), width - pad, y, pctP)

        // полоса: занято, затем кэш
        y += dp(14f)
        val barH = dp(14f)
        bar(y, barH, 1f); p.shader = null; p.color = Ui.TRACK; c.drawRoundRect(r, barH / 2, barH / 2, p)
        val used = v[0].coerceIn(0f, 1f)
        val cache = v[1].coerceIn(0f, 1f - used)
        if (used + cache > 0.01f) {
            bar(y, barH, used + cache); p.color = Ui.withAlpha(Ui.secondary, 0.35f)
            c.drawRoundRect(r, barH / 2, barH / 2, p)
        }
        if (used > 0.01f) {
            bar(y, barH, used); p.shader = usedShader
            c.drawRoundRect(r, barH / 2, barH / 2, p)
        }

        // легенда
        y += barH + dp(20f)
        var x = pad
        x = legend(c, x, y, Ui.primary, ctx.getString(R.string.c_used))
        x = legend(c, x, y, Ui.withAlpha(Ui.secondary, 0.6f), ctx.getString(R.string.c_cache, Human.size(ctx, (snap.memAvail - snap.memFree).coerceAtLeast(0))))
        legend(c, x, y, Ui.TRACK or 0x30000000, ctx.getString(R.string.c_free, Human.size(ctx, snap.memFree)))

        // история занятой памяти
        chart.rect.set(pad, y + dp(26f), width - pad, y + dp(26f) + chartH)
        chart.draw(c)
        y += dp(26f) + chartH

        if (hasSwap) {
            y += dp(26f)
            c.drawText(ctx.getString(R.string.c_swap), pad, y, labelP)
            smallP.textAlign = Paint.Align.RIGHT
            c.drawText(Human.size(ctx, snap.swapTotal - snap.swapFree) + " / " + Human.size(ctx, snap.swapTotal),
                width - pad, y, smallP)
            smallP.textAlign = Paint.Align.LEFT
            y += dp(10f)
            val h2 = dp(6f)
            bar(y, h2, 1f); p.shader = null; p.color = Ui.TRACK; c.drawRoundRect(r, h2 / 2, h2 / 2, p)
            if (v[2] > 0.01f) {
                bar(y, h2, v[2].coerceIn(0f, 1f)); p.color = Ui.tertiary
                c.drawRoundRect(r, h2 / 2, h2 / 2, p)
            }
        }
    }

    private fun bar(top: Float, h: Float, f: Float) {
        val w = width - 2 * pad
        r.set(pad, top, pad + maxOf(w * f, h), top + h)
    }

    private fun legend(c: Canvas, x: Float, y: Float, color: Int, text: String): Float {
        p.shader = null
        p.color = color
        c.drawCircle(x + dp(4f), y - dp(4f), dp(4f), p)
        c.drawText(text, x + dp(12f), y, smallP)
        return x + dp(12f) + smallP.measureText(text) + dp(14f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent) = chart.onTouch(e) || super.onTouchEvent(e)
}
