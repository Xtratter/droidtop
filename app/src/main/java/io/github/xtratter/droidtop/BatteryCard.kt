package io.github.xtratter.droidtop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/** Карточка батареи: мощность прямо сейчас, ток, напряжение, прогноз и график мощности. */
class BatteryCard(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private fun dp(v: Float) = Ui.dp(context, v)
    private val pad = dp(20f)
    private val labelP = Ui.textPaint(ctx, 13f, Ui.medium, Ui.TEXT2)
    private val bigP = Ui.textPaint(ctx, 28f, Ui.bold)
    private val unitP = Ui.textPaint(ctx, 15f, Ui.medium, Ui.TEXT2)
    private val levelP = Ui.textPaint(ctx, 18f, Ui.bold).apply { textAlign = Paint.Align.RIGHT }
    private val smallP = Ui.textPaint(ctx, 12f, Ui.regular, Ui.TEXT2)
    private val chipP = Ui.textPaint(ctx, 12f, Ui.medium)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val chart = Chart(this).apply {
        autoMax = true; maxY = 1f
        format = { String.format(Locale.getDefault(), "%.2f %s", it, ctx.getString(R.string.u_w)) }
    }
    private val chartH = dp(56f)
    private var b: Battery? = null

    init {
        background = GlassDrawable(ctx, 28f)
    }

    fun update(s: Snapshot) {
        b = s.battery
        chart.values = History.power.toArray()
        chart.color = if (b?.charging == true) Ui.OK else Ui.WARN
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = pad * 2 + dp(18f) + dp(38f) + dp(22f) + dp(26f) + chartH
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onDraw(c: Canvas) {
        val bt = b ?: return
        val ctx = context
        var y = pad - labelP.ascent()
        c.drawText(ctx.getString(R.string.c_battery), pad, y, labelP)

        // состояние — «пилюля» справа: зарядка / от сети / разряд
        val (stText, stColor) = when {
            bt.charging -> ctx.getString(R.string.b_charging) to Ui.OK
            bt.plugged -> ctx.getString(R.string.b_plugged) to Ui.primary
            else -> ctx.getString(R.string.b_discharging) to Ui.WARN
        }
        val cw = chipP.measureText(stText) + dp(20f)
        r.set(width - pad - cw, y - dp(18f), width - pad, y + dp(6f))
        p.color = Ui.withAlpha(stColor, 0.18f)
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        chipP.color = stColor
        c.drawText(stText, r.left + dp(10f), r.centerY() - (chipP.ascent() + chipP.descent()) / 2, chipP)

        // мощность крупно, уровень заряда справа
        y += dp(40f)
        val known = bt.currentMa > 0f
        val big = if (known) String.format(Locale.getDefault(), "%.2f", bt.powerW) else "—"
        c.drawText(big, pad, y, bigP)
        c.drawText(" " + ctx.getString(R.string.u_w), pad + bigP.measureText(big), y, unitP)
        levelP.color = if (bt.level <= 15 && !bt.charging) Ui.HOT else Ui.TEXT
        c.drawText("${bt.level}%", width - pad, y, levelP)

        // детали: ток, напряжение, температура, прогноз
        y += dp(22f)
        val parts = ArrayList<String>()
        if (known) parts += ctx.getString(R.string.b_ma, bt.currentMa.toInt())
        if (bt.voltage > 0f) parts += String.format(Locale.getDefault(), "%.2f %s", bt.voltage, ctx.getString(R.string.u_v))
        if (!bt.temp.isNaN()) parts += Fmt.temp(bt.temp)
        if (bt.minutesLeft > 0) parts += ctx.getString(
            if (bt.charging) R.string.b_full_in else R.string.b_left, HumanTime.duration(ctx, bt.minutesLeft))
        c.drawText(Ui.ellipsize(smallP, parts.joinToString("  ·  "), width - 2 * pad), pad, y, smallP)

        chart.rect.set(pad, y + dp(26f), width - pad, y + dp(26f) + chartH)
        chart.draw(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent) = chart.onTouch(e) || super.onTouchEvent(e)
}
