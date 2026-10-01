package io.github.xtratter.droidtop

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.util.Locale

/**
 * Стеклянная плашка поверх других окон: CPU, ядра, память, видеочип, батарея и самые активные процессы.
 * Что показывать, масштаб и ширина — из настроек; при их смене служба создаёт плашку заново.
 */
class OverlayView(ctx: Context, private val scale: Float, widthDp: Int) : View(ctx) {
    /** Части плашки — биты в [Prefs.overlayParts]. */
    object Part {
        const val CPU = 1
        const val TEMP = 2
        const val CORES = 4
        const val RAM = 8
        const val GPU = 16
        const val BAT = 32
        const val ALL = CPU or TEMP or CORES or RAM or GPU or BAT
    }

    private fun dp(v: Float) = Ui.dp(context, v) * scale
    private fun paint(sizeSp: Float, face: android.graphics.Typeface, color: Int) =
        Ui.textPaint(context, sizeSp * scale, face, color)

    private val pad = dp(14f)
    private val w = dp(widthDp.toFloat())
    private val labelP = paint(11f, Ui.medium, Ui.TEXT2)
    private val bigP = paint(26f, Ui.bold, Ui.TEXT)
    private val unitP = paint(13f, Ui.medium, Ui.TEXT2)
    private val rightP = paint(12f, Ui.medium, Ui.TEXT).apply { textAlign = Paint.Align.RIGHT }
    private val nameP = paint(12f, Ui.regular, Ui.TEXT)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TRACK }
    private val r = RectF()
    // без анимации: каждый её кадр заставлял бы систему пересобирать весь экран под оверлеем
    private val smooth = Smooth(this, animate = false)
    private var glass: GlassDrawable? = null
    private var barShader: Shader? = null
    private val coresH = dp(30f)
    private val barH = dp(5f)

    /** Какие части показывать (биты [Part]). */
    var parts = Part.ALL
        set(v) { field = v; requestLayout(); invalidate() }
    var topCount = 3
        set(v) { field = v; snapshot?.let { topProcs = top(it) }; requestLayout(); invalidate() }
    /** Топ процессов по памяти, а не по CPU. */
    var topByMem = false
        set(v) { field = v; snapshot?.let { topProcs = top(it) }; invalidate() }
    var kernelThreads = false
        set(v) { field = v; snapshot?.let { topProcs = top(it) } }
    var bgAlpha = 0.85f
        set(v) { field = v; glass = null; invalidate() }

    /** Самые активные процессы текущего замера — считаем один раз, а не в каждом кадре. */
    private var topProcs: List<ProcInfo> = emptyList()
    private var measuredFor = -1f

    var snapshot: Snapshot? = null
        set(v) {
            field = v
            topProcs = v?.let { top(it) } ?: emptyList()
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
            // высота зависит от набора строк (есть ли GPU, батарея, сколько процессов)
            if (layout(null) != measuredFor) requestLayout()
            invalidate()
        }

    private fun has(part: Int) = parts and part != 0

    private fun top(s: Snapshot) =
        if (topCount <= 0) emptyList()
        else s.procs.asSequence().filter { kernelThreads || !it.kernel }
            .sortedByDescending { if (topByMem) it.rss.toFloat() else it.cpu }.take(topCount).toList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measuredFor = layout(null)
        setMeasuredDimension(w.toInt(), measuredFor.toInt())
    }

    override fun onSizeChanged(nw: Int, nh: Int, oldw: Int, oldh: Int) {
        glass = null
    }

    override fun onDraw(c: Canvas) {
        val g = glass ?: GlassDrawable(context, 22f * scale, Ui.withAlpha(Ui.surface, bgAlpha), tonal = false).also {
            it.setBounds(0, 0, width, height); glass = it
        }
        g.draw(c)
        layout(c)
    }

    /**
     * Раскладка и рисование одним проходом: без холста только считает высоту,
     * поэтому размер плашки всегда совпадает с тем, что на ней нарисовано.
     */
    private fun layout(c: Canvas?): Float {
        var y = pad
        var first = true
        fun gap(v: Float) { if (!first) y += dp(v); first = false }
        val s = snapshot
        val v = smooth.cur
        val right = w - pad

        // CPU: подпись и температура, под ними большая цифра
        val t = s?.let { if (!it.cpuTemp.isNaN()) it.cpuTemp else it.batteryTemp } ?: Float.NaN
        val showTemp = has(Part.TEMP) && !t.isNaN()
        if (has(Part.CPU) || showTemp) {
            gap(0f)
            if (c != null) {
                val base = y + dp(11f)
                c.drawText(if (has(Part.CPU)) "CPU" else "TEMP", pad, base, labelP)
                if (showTemp) {
                    rightP.color = Ui.load(t, 60f, 80f)
                    c.drawText(Fmt.temp(t), right, base, rightP)
                }
            }
            y += dp(14f)
            if (has(Part.CPU)) {
                if (c != null && s != null && v.size >= 2) {
                    val base = y + dp(27f)
                    if (!s.cpu.isNaN()) {
                        val big = String.format(Locale.getDefault(), "%.0f", v[0].coerceIn(0f, 100f))
                        bigP.color = Ui.load(v[0])
                        c.drawText(big, pad, base, bigP)
                        c.drawText("%", pad + bigP.measureText(big) + dp(2f), base, unitP)
                    } else {
                        val f = s.cores.maxOfOrNull { it.freqKHz } ?: 0L
                        bigP.color = Ui.TEXT
                        val big = String.format(Locale.getDefault(), "%.2f", f / 1e6)
                        c.drawText(big, pad, base, bigP)
                        c.drawText(context.getString(R.string.u_ghz), pad + bigP.measureText(big) + dp(3f), base, unitP)
                    }
                }
                y += dp(30f)
            }
        }

        // столбики ядер
        if (has(Part.CORES)) {
            gap(8f)
            if (c != null && s != null) {
                val top = y
                if (barShader == null) barShader = LinearGradient(0f, top + coresH, 0f, top, Ui.primary,
                    Ui.mix(Ui.primary, 0xFFFFFFFF.toInt(), 0.45f), Shader.TileMode.CLAMP)
                val n = s.cores.size.coerceAtLeast(1)
                val g = dp(4f)
                val bw = (w - 2 * pad - g * (n - 1)) / n
                for (i in 0 until s.cores.size) {
                    val left = pad + i * (bw + g)
                    val u = s.cores[i].usage
                    p.shader = null
                    if (!u.isNaN() && u >= 85f) p.color = Ui.HOT
                    else { p.color = 0xFF000000.toInt(); p.shader = barShader }   // альфа цвета умножается на градиент
                    PillBar.draw(c, left, top, left + bw, top + coresH, minOf(bw / 2, dp(5f)), v.getOrElse(i + 2) { 0f }, trackP, p)
                }
                p.shader = null
            }
            y += coresH
        }

        /** Значение из частей «a · b · c»: на узкой плашке лишние части справа отбрасываем. */
        fun fit(label: String, parts: List<String>): String {
            val room = w - 2 * pad - labelP.measureText(label) - dp(8f)
            var n = parts.size
            while (n > 1 && rightP.measureText(parts.take(n).joinToString(" · ")) > room) n--
            return Ui.ellipsize(rightP, parts.take(n).joinToString(" · "), room)
        }

        // строка «подпись — значение» и полоска под ней
        fun meter(label: String, parts: List<String>, frac: Float, color: Int) {
            gap(10f)
            if (c != null) {
                val base = y + dp(11f)
                c.drawText(label, pad, base, labelP)
                rightP.color = Ui.TEXT
                c.drawText(fit(label, parts), right, base, rightP)
                val top = y + dp(18f)
                r.set(pad, top, right, top + barH)
                p.color = Ui.TRACK; c.drawRoundRect(r, barH / 2, barH / 2, p)
                r.right = pad + (w - 2 * pad) * frac.coerceIn(0f, 1f)
                p.color = color; c.drawRoundRect(r, barH / 2, barH / 2, p)
            }
            y += dp(18f) + barH
        }

        if (has(Part.RAM) && s != null) {
            val frac = if (v.size >= 2) v[1] else 0f
            meter("RAM", listOf(Fmt.size(s.memTotal - s.memAvail) + " / " + Fmt.size(s.memTotal)), frac, Ui.load(frac * 100, 75f, 90f))
        }

        val gp = s?.gpu
        if (has(Part.GPU) && gp != null) {
            val parts = ArrayList<String>()
            if (!gp.busy.isNaN()) parts += Fmt.pct(gp.busy) + "%"
            if (gp.freqMHz > 0) parts += "${gp.freqMHz} " + context.getString(R.string.u_mhz)
            if (!gp.temp.isNaN()) parts += Fmt.temp(gp.temp)
            meter("GPU", parts, gp.fraction,
                if (!gp.busy.isNaN()) Ui.load(gp.busy, 50f, 85f) else Ui.secondary)
        }

        // батарея: мощность и заряд
        val b = s?.battery
        if (has(Part.BAT) && b != null && b.currentMa > 0f) {
            gap(10f)
            if (c != null) {
                val base = y + dp(11f)
                val label = if (b.charging) "⚡ BAT" else "BAT"
                c.drawText(label, pad, base, labelP)
                rightP.color = if (b.charging) Ui.OK else Ui.TEXT
                c.drawText(fit(label, listOf(String.format(Locale.getDefault(), "%.2f %s", b.powerW,
                    context.getString(R.string.u_w)), "${b.level}%")), right, base, rightP)
            }
            y += dp(14f)
        }

        // топ процессов: по CPU или по памяти
        if (topProcs.isNotEmpty()) {
            gap(6f)
            for (pr in topProcs) {
                if (c != null) {
                    val base = y + dp(15f)
                    val value: String
                    if (topByMem) {
                        value = Fmt.size(pr.rss)
                        rightP.color = Ui.TEXT
                    } else {
                        value = Fmt.pct(pr.cpu) + "%"
                        rightP.color = Ui.load(pr.cpu, 10f, 50f).let { if (pr.cpu < 0.05f) Ui.TEXT3 else it }
                    }
                    c.drawText(value, right, base, rightP)
                    val room = w - 2 * pad - rightP.measureText(value) - dp(8f)
                    nameP.color = if (pr.pkg != null) Ui.TEXT else Ui.TEXT2
                    c.drawText(Ui.ellipsize(nameP, pr.title, room), pad, base, nameP)
                }
                y += dp(20f)
            }
        }
        return y + pad
    }
}
