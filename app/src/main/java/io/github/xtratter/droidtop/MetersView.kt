package io.github.xtratter.droidtop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Верхняя панель как в htop: полоски ядер, памяти, подкачки и строка со сводкой. */
class MetersView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private var charW = 0f
    private var lineH = 0f
    private var ascent = 0f
    private var lines = 0

    var snapshot: Snapshot? = null
        set(v) {
            field = v
            if (countLines() != lines) requestLayout()
            invalidate()
        }

    init { setFont(12) }

    fun setFont(sp: Int) {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), resources.displayMetrics)
        charW = paint.measureText("0")
        val fm = paint.fontMetrics
        lineH = ceil((fm.descent - fm.ascent) * 1.15f)
        ascent = -fm.ascent
        requestLayout()
        invalidate()
    }

    private fun countLines(): Int {
        val s = snapshot ?: return 0
        return (s.cores.size + 1) / 2 + 1 + (if (s.swapTotal > 0) 1 else 0) + 3
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        lines = countLines()
        val h = (lines * lineH).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(c: Canvas) {
        val s = snapshot ?: return
        val totalChars = ((width - paddingLeft - paddingRight) / charW).toInt()
        var y = paddingTop + ascent

        // ядра в две колонки
        val half = (totalChars - 1) / 2
        val digits = (s.cores.size - 1).toString().length
        val rows = (s.cores.size + 1) / 2
        for (r in 0 until rows) {
            for (k in 0..1) {
                val i = r + k * rows
                val core = s.cores.getOrNull(i) ?: continue
                val x = paddingLeft + k * (half + 1) * charW
                drawCore(c, x, y, half, i.toString().padStart(digits), core)
            }
            y += lineH
        }

        // память: зелёное — занято, жёлтое — кэш, который система может освободить
        val used = s.memTotal - s.memAvail
        val cache = (s.memAvail - s.memFree).coerceAtLeast(0)
        drawBar(c, paddingLeft.toFloat(), y, totalChars, "Mem",
            listOf(frac(used, s.memTotal) to Palette.GREEN, frac(cache, s.memTotal) to Palette.YELLOW),
            Fmt.size(used) + "/" + Fmt.size(s.memTotal))
        y += lineH
        if (s.swapTotal > 0) {
            val su = s.swapTotal - s.swapFree
            drawBar(c, paddingLeft.toFloat(), y, totalChars, "Swp",
                listOf(frac(su, s.swapTotal) to Palette.RED), Fmt.size(su) + "/" + Fmt.size(s.swapTotal))
            y += lineH
        }

        val r = resources
        val x0 = paddingLeft.toFloat()
        drawPairs(c, x0, y, listOf(
            r.getString(R.string.m_tasks) to null,
            s.procs.size.toString() to Palette.BRIGHT,
            r.getString(R.string.m_threads) to null,
            s.threads.toString() to Palette.BRIGHT,
            r.getString(R.string.m_running) to null,
            s.running.toString() to Palette.GREEN,
        ))
        y += lineH

        val up = s.uptime.toLong()
        val days = up / 86400
        val upText = (if (days > 0) r.getString(R.string.m_days, days) + " " else "") + Fmt.hms(up)
        val loadParts = s.load?.let { l ->
            listOf(r.getString(R.string.m_load) to null, l.joinToString(" ") { Fmt.load(it) } to Palette.BRIGHT)
        } ?: emptyList()
        drawPairs(c, x0, y, loadParts + listOf(r.getString(R.string.m_uptime) to null, upText to Palette.BRIGHT))
        y += lineH

        val cpuParts = ArrayList<Pair<String, Int?>>()
        cpuParts += r.getString(R.string.m_cpu) to null
        cpuParts += (if (s.cpu.isNaN()) "—" else Fmt.pct(s.cpu) + "%") to
            (if (s.cpu.isNaN()) Palette.DIM else Palette.load(s.cpu, 50f, 85f))
        if (!s.cpuTemp.isNaN()) cpuParts += Fmt.temp(s.cpuTemp) to Palette.load(s.cpuTemp, 60f, 80f)
        if (!s.batteryTemp.isNaN()) {
            cpuParts += r.getString(R.string.m_battery) to null
            cpuParts += Fmt.temp(s.batteryTemp) to Palette.load(s.batteryTemp, 40f, 45f)
        }
        drawPairs(c, x0, y, cpuParts)
    }

    private fun frac(a: Long, b: Long) = if (b > 0) (a.toFloat() / b).coerceIn(0f, 1f) else 0f

    private fun drawCore(c: Canvas, x: Float, y: Float, width: Int, label: String, core: CoreInfo) {
        val inner = width - label.length - 2
        val hasUsage = !core.usage.isNaN()
        val freq = if (core.freqKHz > 0) Fmt.freq(core.freqKHz) else ""
        val text = when {
            hasUsage && freq.isNotEmpty() && inner >= 16 -> Fmt.pct(core.usage) + "% " + freq
            hasUsage -> Fmt.pct(core.usage) + "%"
            freq.isNotEmpty() -> freq
            else -> "off"
        }
        // без root загрузка ядра недоступна — показываем частоту относительно максимальной (синим)
        val (value, color) = when {
            hasUsage -> core.usage / 100f to Palette.load(core.usage, 50f, 85f).let { if (it == Palette.TEXT || it == Palette.DIM) Palette.GREEN else it }
            core.maxKHz > 0 && core.freqKHz > 0 -> core.freqKHz.toFloat() / core.maxKHz to Palette.BLUE
            else -> 0f to Palette.DIM
        }
        drawBar(c, x, y, width, label, listOf(value to color), text)
    }

    /** Полоска вида «Mem[||||||||      1.2G/7.5G]». */
    private fun drawBar(c: Canvas, x: Float, y: Float, width: Int, label: String, parts: List<Pair<Float, Int>>, text: String) {
        val inner = (width - label.length - 2).coerceAtLeast(1)
        var cx = x
        paint.color = Palette.LABEL
        c.drawText(label, cx, y, paint); cx += label.length * charW
        paint.color = Palette.BRIGHT
        c.drawText("[", cx, y, paint); cx += charW
        val barRoom = (inner - text.length - 1).coerceAtLeast(0)
        var drawn = 0
        for ((f, color) in parts) {
            val n = ((f * inner).roundToInt()).coerceAtMost(barRoom - drawn).coerceAtLeast(0)
            if (n == 0) continue
            paint.color = color
            c.drawText("|".repeat(n), cx + drawn * charW, y, paint)
            drawn += n
        }
        paint.color = Palette.TEXT
        c.drawText(text.takeLast(inner), cx + (inner - text.length).coerceAtLeast(0) * charW, y, paint)
        paint.color = Palette.BRIGHT
        c.drawText("]", cx + inner * charW, y, paint)
    }

    /** Текст из кусочков: null-цвет — подпись (голубая), иначе значение. */
    private fun drawPairs(c: Canvas, x: Float, y: Float, parts: List<Pair<String, Int?>>) {
        var cx = x
        for ((t, color) in parts) {
            paint.color = color ?: Palette.LABEL
            c.drawText(t, cx, y, paint)
            cx += (t.length + 1) * charW
        }
    }
}
