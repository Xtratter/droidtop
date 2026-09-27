package io.github.xtratter.droidtop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import kotlin.math.ceil

/** Колонки таблицы. prio: 0 — всегда видна, чем больше, тем раньше прячется на узком экране. */
enum class Col(val title: String, val width: Int, val right: Boolean, val prio: Int, val sort: Sort) {
    PID("PID", 5, true, 0, Sort.PID),
    USER("USER", 8, false, 3, Sort.USER),
    NI("NI", 3, true, 5, Sort.NI),
    S("S", 1, false, 1, Sort.STATE),
    CPU("CPU%", 5, true, 0, Sort.CPU),
    MEM("MEM%", 4, true, 0, Sort.MEM),
    RES("RES", 5, true, 2, Sort.MEM),
    THR("THR", 3, true, 6, Sort.THR),
    TIME("TIME+", 8, true, 4, Sort.TIME),
    CMD("", 0, false, 0, Sort.NAME),
}

/** Общая раскладка колонок для заголовка и строк: моноширинный шрифт, ширина в символах. */
class Table(private val ctx: Context) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    var charW = 0f; private set
    var rowH = 0; private set
    var baseline = 0f; private set
    var cols: List<Col> = listOf(Col.PID, Col.CPU, Col.MEM, Col.CMD); private set
    private var cmdChars = 20
    private var widthPx = 0
    private val pad = dp(4f)
    var cmdTitle = "Command"

    fun setFont(sp: Int) {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), ctx.resources.displayMetrics)
        charW = paint.measureText("0")
        val fm = paint.fontMetrics
        val h = fm.descent - fm.ascent
        rowH = ceil(h * 1.5f).toInt()
        baseline = (rowH - h) / 2 - fm.ascent
        if (widthPx > 0) layout(widthPx)
    }

    /** Подобрать набор колонок под ширину в пикселях. Возвращает true, если раскладка поменялась. */
    fun layout(width: Int): Boolean {
        widthPx = width
        val total = ((width - 2 * pad) / charW).toInt()
        val chosen = Col.entries.filter { it.prio == 0 }.toMutableSet()
        var used = chosen.filter { it != Col.CMD }.sumOf { it.width + 1 }
        for (prio in 1..6) for (c in Col.entries.filter { it.prio == prio }) {
            if (used + c.width + 1 + MIN_CMD <= total) { chosen += c; used += c.width + 1 }
        }
        val newCols = Col.entries.filter { it in chosen }
        val newCmd = (total - used).coerceAtLeast(MIN_CMD)
        val changed = newCols != cols || newCmd != cmdChars
        cols = newCols
        cmdChars = newCmd
        return changed
    }

    /** Нарисовать строку: текст и цвет для каждой колонки. */
    fun drawRow(c: Canvas, text: (Col) -> String, color: (Col) -> Int) {
        var x = pad
        for (col in cols) {
            val w = if (col == Col.CMD) cmdChars else col.width
            var t = text(col)
            if (col == Col.CMD && t.length > w) t = t.take(w - 1) + "…"
            val tx = if (col.right && t.length < w) x + (w - t.length) * charW else x
            paint.color = color(col)
            c.drawText(t, tx, baseline, paint)
            x += (w + 1) * charW
        }
    }

    /** Левая и правая граница колонки в пикселях (для подсветки и нажатий в заголовке). */
    fun bounds(target: Col): Pair<Float, Float> {
        var x = pad
        for (col in cols) {
            val w = if (col == Col.CMD) cmdChars else col.width
            if (col == target) return (x - charW / 2) to (x + (w + 0.5f) * charW)
            x += (w + 1) * charW
        }
        return 0f to 0f
    }

    fun colAt(px: Float): Col? = cols.firstOrNull { val (l, r) = bounds(it); px in l..r }

    fun titleOf(col: Col) = if (col == Col.CMD) cmdTitle else col.title

    private fun dp(v: Float) = v * ctx.resources.displayMetrics.density

    private companion object {
        const val MIN_CMD = 14
    }
}
