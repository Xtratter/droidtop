package io.github.xtratter.droidtop

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.util.Locale

/** Стеклянная плашка поверх других окон: CPU, ядра, память и самые прожорливые процессы. */
class OverlayView(ctx: Context) : View(ctx) {
    private fun dp(v: Float) = Ui.dp(context, v)
    private val pad = dp(14f)
    private val w = dp(184f)
    private val labelP = Ui.textPaint(ctx, 11f, Ui.medium, Ui.TEXT2)
    private val bigP = Ui.textPaint(ctx, 26f, Ui.bold)
    private val unitP = Ui.textPaint(ctx, 13f, Ui.medium, Ui.TEXT2)
    private val rightP = Ui.textPaint(ctx, 12f, Ui.medium).apply { textAlign = Paint.Align.RIGHT }
    private val nameP = Ui.textPaint(ctx, 12f, Ui.regular, Ui.TEXT)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TRACK }
    private val r = RectF()
    private val smooth = Smooth(this)
    private var glass: GlassDrawable? = null
    private var barShader: Shader? = null
    private val coresH = dp(30f)

    var snapshot: Snapshot? = null
        set(v) {
            val rows = v?.let { top(it).size }
            val relayout = field?.let { top(it).size } != rows
            field = v
            if (v != null) {
                val f = FloatArray(v.cores.size + 2)
                f[0] = if (!v.cpu.isNaN()) v.cpu else 0f
                f[1] = (v.memTotal - v.memAvail).toFloat() / v.memTotal.coerceAtLeast(1)
                v.cores.forEachIndexed { i, c ->
                    f[i + 2] = when {
                        !c.usage.isNaN() -> c.usage / 100f
                        c.maxKHz > 0 -> c.freqKHz.toFloat() / c.maxKHz
                        else -> 0f
                    }
                }
                smooth.set(f)
            }
            if (relayout) requestLayout()
            invalidate()
        }
    var topCount = 3
    var kernelThreads = false
    var bgAlpha = 0.85f
        set(v) { field = v; glass = null; invalidate() }

    private fun top(s: Snapshot) =
        if (topCount <= 0) emptyList()
        else s.procs.asSequence().filter { kernelThreads || !it.kernel }
            .sortedByDescending { it.cpu }.take(topCount).toList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = snapshot?.let { top(it).size } ?: 0
        var h = pad * 2 + dp(14f) + dp(32f) + dp(8f) + coresH + dp(12f) + dp(14f) + dp(10f)
        if (n > 0) h += dp(8f) + n * dp(20f)
        setMeasuredDimension(w.toInt(), h.toInt())
    }

    override fun onSizeChanged(nw: Int, nh: Int, oldw: Int, oldh: Int) {
        glass = null
    }

    override fun onDraw(c: Canvas) {
        val g = glass ?: GlassDrawable(context, 22f, Ui.withAlpha(0xFF16181E.toInt(), bgAlpha)).also {
            it.setBounds(0, 0, width, height); glass = it
        }
        g.draw(c)
        val s = snapshot ?: return
        val v = smooth.cur
        if (v.size < 2) return

        // CPU: подпись, температура, большая цифра
        var y = pad - labelP.ascent()
        c.drawText("CPU", pad, y, labelP)
        val t = if (!s.cpuTemp.isNaN()) s.cpuTemp else s.batteryTemp
        if (!t.isNaN()) { rightP.color = Ui.load(t, 60f, 80f); c.drawText(Fmt.temp(t), width - pad, y, rightP) }
        y += dp(32f)
        if (!s.cpu.isNaN()) {
            val big = String.format(Locale.getDefault(), "%.0f", v[0].coerceIn(0f, 100f))
            bigP.color = Ui.load(v[0])
            c.drawText(big, pad, y, bigP)
            c.drawText("%", pad + bigP.measureText(big) + dp(2f), y, unitP)
        } else {
            val f = s.cores.maxOfOrNull { it.freqKHz } ?: 0L
            bigP.color = Ui.TEXT
            val big = String.format(Locale.getDefault(), "%.2f", f / 1e6)
            c.drawText(big, pad, y, bigP)
            c.drawText(context.getString(R.string.u_ghz), pad + bigP.measureText(big) + dp(3f), y, unitP)
        }

        // столбики ядер
        val top = y + dp(8f)
        if (barShader == null) barShader = LinearGradient(0f, top + coresH, 0f, top, Ui.primary, Ui.mix(Ui.primary, 0xFFFFFFFF.toInt(), 0.45f), Shader.TileMode.CLAMP)
        val n = s.cores.size.coerceAtLeast(1)
        val gap = dp(4f)
        val bw = (width - 2 * pad - gap * (n - 1)) / n
        for (i in 0 until s.cores.size) {
            val left = pad + i * (bw + gap)
            val u = s.cores[i].usage
            p.shader = null
            if (!u.isNaN() && u >= 85f) p.color = Ui.HOT else p.shader = barShader
            PillBar.draw(c, left, top, left + bw, top + coresH, minOf(bw / 2, dp(5f)), v.getOrElse(i + 2) { 0f }, trackP, p)
        }
        p.shader = null

        // память
        y = top + coresH + dp(12f) - labelP.ascent()
        c.drawText("RAM", pad, y, labelP)
        rightP.color = Ui.TEXT
        c.drawText(Fmt.size(s.memTotal - s.memAvail) + " / " + Fmt.size(s.memTotal), width - pad, y, rightP)
        y += dp(7f)
        val bh = dp(5f)
        r.set(pad, y, width - pad, y + bh)
        p.color = Ui.TRACK; c.drawRoundRect(r, bh / 2, bh / 2, p)
        r.right = pad + (width - 2 * pad) * v[1].coerceIn(0f, 1f)
        p.color = Ui.load(v[1] * 100, 75f, 90f); c.drawRoundRect(r, bh / 2, bh / 2, p)
        y += bh + dp(8f)

        // топ процессов
        val procs = top(s)
        if (procs.isNotEmpty()) {
            y += dp(4f)
            for (pr in procs) {
                y += dp(20f)
                val value = Fmt.pct(pr.cpu) + "%"
                rightP.color = Ui.load(pr.cpu, 10f, 50f).let { if (pr.cpu < 0.05f) Ui.TEXT3 else it }
                c.drawText(value, width - pad, y, rightP)
                val room = width - 2 * pad - rightP.measureText(value) - dp(8f)
                nameP.color = if (pr.pkg != null) Ui.TEXT else Ui.TEXT2
                c.drawText(Ui.ellipsize(nameP, pr.title, room), pad, y, nameP)
            }
        }
    }
}
