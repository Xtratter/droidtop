package io.github.xtratter.droidtop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View

/** Отдельный график с подписью — для карточки процесса. */
class ChartView(ctx: Context, private val title: String) : View(ctx) {
    private fun dp(v: Float) = Ui.dp(context, v)
    val chart = Chart(this)
    private val titleP = Ui.textPaint(ctx, 12f, Ui.medium, Ui.TEXT2)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(112f).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.drawText(title, dp(4f), -titleP.ascent(), titleP)
        chart.rect.set(dp(4f), dp(40f), width - dp(4f), height - dp(8f))
        chart.draw(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent) = chart.onTouch(e) || super.onTouchEvent(e)
}
