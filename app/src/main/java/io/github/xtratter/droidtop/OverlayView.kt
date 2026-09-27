package io.github.xtratter.droidtop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil

/** Маленькая плашка поверх других окон: CPU, ядра, память и самые прожорливые процессы. */
class OverlayView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density
    private val pad = 6 * dp
    private val coreBarH = 12 * dp
    private var charW = 0f
    private var lineH = 0f
    private var ascent = 0f
    private val rect = RectF()

    var snapshot: Snapshot? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var topCount = 3
    var kernelThreads = false
    var bgAlpha = 0.85f
        set(v) { field = v; invalidate() }

    init {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        charW = paint.measureText("0")
        val fm = paint.fontMetrics
        lineH = ceil((fm.descent - fm.ascent) * 1.1f)
        ascent = -fm.ascent
    }

    private fun top(s: Snapshot) =
        if (topCount <= 0) emptyList()
        else s.procs.asSequence().filter { kernelThreads || !it.kernel }
            .sortedByDescending { it.cpu }.take(topCount).toList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = snapshot
        val textLines = 2 + (s?.let { top(it).size } ?: 0)
        val w = pad * 2 + charW * WIDTH_CHARS
        val h = pad * 2 + lineH * textLines + coreBarH + 4 * dp
        setMeasuredDimension(w.toInt(), h.toInt())
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        bg.color = 0x000000 or ((bgAlpha * 255).toInt() shl 24)
        c.drawRoundRect(rect, 8 * dp, 8 * dp, bg)
        val s = snapshot ?: return
        var y = pad + ascent

        // CPU и температура
        var x = pad
        x = put(c, "CPU ", x, y, Palette.LABEL)
        if (!s.cpu.isNaN()) {
            x = put(c, Fmt.pct(s.cpu) + "% ", x, y, Palette.load(s.cpu, 50f, 85f).let { if (it == Palette.DIM) Palette.TEXT else it })
        } else {
            val f = s.cores.maxOfOrNull { it.freqKHz } ?: 0L
            if (f > 0) x = put(c, Fmt.freq(f) + " ", x, y, Palette.BLUE)
        }
        val t = if (!s.cpuTemp.isNaN()) s.cpuTemp else s.batteryTemp
        if (!t.isNaN()) put(c, Fmt.temp(t), x, y, Palette.load(t, 60f, 80f).let { if (it == Palette.DIM) Palette.TEXT else it })
        y += lineH - ascent

        // столбики ядер
        val n = s.cores.size.coerceAtLeast(1)
        val gap = 2 * dp
        val bw = (width - 2 * pad - gap * (n - 1)) / n
        for ((i, core) in s.cores.withIndex()) {
            val left = pad + i * (bw + gap)
            val (f, color) = when {
                !core.usage.isNaN() -> core.usage / 100f to Palette.load(core.usage, 50f, 85f).let { if (it == Palette.TEXT || it == Palette.DIM) Palette.GREEN else it }
                core.maxKHz > 0 && core.freqKHz > 0 -> core.freqKHz.toFloat() / core.maxKHz to Palette.BLUE
                else -> 0f to Palette.DIM
            }
            paint.color = 0x33FFFFFF
            c.drawRect(left, y + 2 * dp, left + bw, y + 2 * dp + coreBarH, paint)
            paint.color = color
            val top = y + 2 * dp + coreBarH * (1 - f.coerceIn(0f, 1f))
            c.drawRect(left, top, left + bw, y + 2 * dp + coreBarH, paint)
        }
        y += coreBarH + 4 * dp + ascent

        // память
        x = put(c, "RAM ", pad, y, Palette.LABEL)
        put(c, Fmt.size(s.memTotal - s.memAvail) + "/" + Fmt.size(s.memTotal), x, y, Palette.TEXT)
        y += lineH

        for (p in top(s)) {
            val v = Fmt.pct(p.cpu).padStart(4)
            val nx = put(c, "$v ", pad, y, Palette.load(p.cpu, 10f, 50f).let { if (it == Palette.DIM) Palette.TEXT else it })
            val room = WIDTH_CHARS - v.length - 1
            val name = p.title.let { if (it.length > room) it.take(room - 1) + "…" else it }
            put(c, name, nx, y, if (p.pkg != null) Palette.CYAN else Palette.BRIGHT)
            y += lineH
        }
    }

    private fun put(c: Canvas, t: String, x: Float, y: Float, color: Int): Float {
        paint.color = color
        c.drawText(t, x, y, paint)
        return x + t.length * charW
    }

    private companion object {
        const val WIDTH_CHARS = 20
    }
}
