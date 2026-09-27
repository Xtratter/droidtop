package io.github.xtratter.droidtop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Строки-«пилюли» со сводкой: задачи, потоки, нагрузка, время работы, батарея. Переносятся по ширине. */
class ChipsView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private fun dp(v: Float) = Ui.dp(context, v)
    private val labelP = Ui.textPaint(ctx, 12f, Ui.regular, Ui.TEXT2)
    private val valueP = Ui.textPaint(ctx, 13f, Ui.medium)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x17FFFFFF }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x2EFFFFFF
    }
    private val r = RectF()
    private val chipH = dp(34f)
    private val gap = dp(8f)
    private var items: List<Triple<String, String, Int>> = emptyList()

    fun update(s: Snapshot) {
        val c = context
        val list = ArrayList<Triple<String, String, Int>>()
        list += Triple(c.getString(R.string.i_tasks), s.procs.size.toString(), Ui.TEXT)
        list += Triple(c.getString(R.string.i_threads), s.threads.toString(), Ui.TEXT)
        if (s.full) list += Triple(c.getString(R.string.i_running), s.running.toString(), Ui.OK)
        s.load?.let { l -> list += Triple(c.getString(R.string.i_load), l.joinToString(" · ") { Fmt.load(it) }, Ui.TEXT) }
        val up = s.uptime.toLong()
        val d = up / 86400
        list += Triple(c.getString(R.string.i_uptime),
            (if (d > 0) c.getString(R.string.m_days, d) + " " else "") + Fmt.hms(up), Ui.TEXT)
        val relayout = list.size != items.size || list.zip(items).any { (a, b) -> a.second.length != b.second.length }
        items = list
        if (relayout) requestLayout()
        invalidate()
    }

    private fun chipW(t: Triple<String, String, Int>) =
        labelP.measureText(t.first) + dp(6f) + valueP.measureText(t.second) + dp(28f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        var rows = if (items.isEmpty()) 0 else 1
        var x = 0f
        for (t in items) {
            val cw = chipW(t)
            if (x > 0 && x + cw > w) { rows++; x = 0f }
            x += cw + gap
        }
        setMeasuredDimension(w, (rows * chipH + (rows - 1).coerceAtLeast(0) * gap).toInt())
    }

    override fun onDraw(c: Canvas) {
        var x = 0f
        var y = 0f
        for (t in items) {
            val cw = chipW(t)
            if (x > 0 && x + cw > width) { x = 0f; y += chipH + gap }
            r.set(x, y, x + cw, y + chipH)
            c.drawRoundRect(r, chipH / 2, chipH / 2, fill)
            r.inset(dp(0.5f), dp(0.5f))
            c.drawRoundRect(r, chipH / 2, chipH / 2, edge)
            val base = y + chipH / 2 - (valueP.ascent() + valueP.descent()) / 2
            c.drawText(t.first, x + dp(14f), base, labelP)
            valueP.color = t.third
            c.drawText(t.second, x + dp(14f) + labelP.measureText(t.first) + dp(6f), base, valueP)
            x += cw + gap
        }
    }
}
