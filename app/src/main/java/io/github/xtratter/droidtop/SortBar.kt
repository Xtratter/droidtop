package io.github.xtratter.droidtop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Ряд чипов сортировки в стиле Material 3: выбранный залит акцентом и показывает направление. */
class SortBar(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private fun dp(v: Float) = Ui.dp(context, v)
    private val textP = Ui.textPaint(ctx, 13f, Ui.medium)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x33FFFFFF }
    private val r = RectF()
    private val h = dp(36f)
    private val gap = dp(8f)

    private val options = listOf(
        Sort.CPU to R.string.sort_cpu, Sort.MEM to R.string.sort_mem, Sort.TIME to R.string.sort_time,
        Sort.NAME to R.string.sort_name, Sort.PID to R.string.sort_pid, Sort.USER to R.string.sort_user,
        Sort.THR to R.string.sort_thr,
    )
    var sort = Sort.CPU
    var asc = false
    var onSort: ((Sort) -> Unit)? = null

    private fun label(i: Int): String {
        val (s, res) = options[i]
        return context.getString(res) + if (s == sort) (if (asc) "  ↑" else "  ↓") else ""
    }

    private fun chipW(i: Int) = textP.measureText(label(i)) + dp(32f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = options.indices.sumOf { chipW(it).toDouble() } + gap * (options.size - 1) + dp(4f)
        setMeasuredDimension(w.toInt(), h.toInt())
    }

    override fun onDraw(c: Canvas) {
        var x = 0f
        for (i in options.indices) {
            val w = chipW(i)
            r.set(x, 0f, x + w, h)
            val sel = options[i].first == sort
            if (sel) {
                p.color = Ui.primary
                c.drawRoundRect(r, h / 2, h / 2, p)
            } else {
                p.color = 0x12FFFFFF
                c.drawRoundRect(r, h / 2, h / 2, p)
                r.inset(dp(0.5f), dp(0.5f))
                c.drawRoundRect(r, h / 2, h / 2, edge)
            }
            textP.color = if (sel) Ui.ON_ACCENT else Ui.TEXT2
            c.drawText(label(i), x + dp(16f), h / 2 - (textP.ascent() + textP.descent()) / 2, textP)
            x += w + gap
        }
    }

    fun refresh() { requestLayout(); invalidate() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            var x = 0f
            for (i in options.indices) {
                val w = chipW(i)
                if (e.x in x..x + w) { onSort?.invoke(options[i].first); break }
                x += w + gap
            }
        }
        return true
    }
}
