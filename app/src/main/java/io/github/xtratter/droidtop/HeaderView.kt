package io.github.xtratter.droidtop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Строка заголовков колонок; нажатие на колонку меняет сортировку. */
class HeaderView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    var table: Table? = null
    var sort = Sort.CPU
    var asc = false
    var onSort: ((Col) -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), table?.rowH ?: 0)
    }

    override fun onDraw(c: Canvas) {
        val t = table ?: return
        c.drawColor(Palette.HEADER_BG)
        val sorted = t.cols.firstOrNull { it.sort == sort && (it != Col.RES || Col.MEM !in t.cols) }
        if (sorted != null) {
            val (l, r) = t.bounds(sorted)
            t.paint.color = Palette.HEADER_SORT_BG
            c.drawRect(l, 0f, r, height.toFloat(), t.paint)
        }
        t.drawRow(c, { col ->
            val title = t.titleOf(col)
            if (col == sorted) title + if (asc) "▲" else "▼" else title
        }, { Palette.HEADER_TEXT })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            table?.colAt(e.x)?.let { onSort?.invoke(it) }
        }
        return true
    }
}
