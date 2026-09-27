package io.github.xtratter.droidtop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Что показывать справа в строке — зависит от выбранной сортировки. */
enum class Metric { CPU, MEM, TIME }

/** Строка-карточка процесса: значок, название, PID и пользователь, справа — главная метрика с полоской. */
class ProcItemView(ctx: Context) : View(ctx) {
    private fun dp(v: Float) = Ui.dp(context, v)
    private val titleP = Ui.textPaint(ctx, 15f, Ui.medium)
    private val subP = Ui.textPaint(ctx, 12f, Ui.regular, Ui.TEXT2)
    private val valueP = Ui.textPaint(ctx, 15f, Ui.bold).apply { textAlign = Paint.Align.RIGHT }
    private val letterP = Ui.textPaint(ctx, 17f, Ui.medium).apply { textAlign = Paint.Align.CENTER }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val glass = GlassDrawable(ctx, 20f, 0x12FFFFFF)
    private val side = dp(12f)
    private val vgap = dp(3f)
    private val iconSize = dp(40f)
    private val ownPid = android.os.Process.myPid()

    var proc: ProcInfo? = null
    var metric = Metric.CPU
    var maxValue = 1f          // для относительной полоски памяти и времени
    var clkTck = 100L

    init {
        foreground = Ui.ripple(ctx, 20f, side, vgap)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (dp(66f) + 2 * vgap).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        glass.setBounds(side.toInt(), vgap.toInt(), (w - side).toInt(), (h - vgap).toInt())
    }

    override fun onDraw(c: Canvas) {
        val pr = proc ?: return
        glass.draw(c)
        val ctx = context
        val cy = height / 2f
        val ix = side + dp(12f)

        // значок: настоящий значок приложения или тональный кружок с буквой
        val icon = AppIcons.get(pr.pkg)
        if (icon != null) {
            c.drawBitmap(icon, ix, cy - iconSize / 2, null)
        } else {
            val hue = ((pr.name.hashCode() and 0x7fffffff) % 360).toFloat()
            p.color = if (pr.kernel) 0x22FFFFFF else android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.42f))
            c.drawCircle(ix + iconSize / 2, cy, iconSize / 2, p)
            letterP.color = if (pr.kernel) Ui.TEXT3 else Ui.TEXT
            val ch = pr.title.trimStart('[', '/', '.', '@').firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            c.drawText(ch, ix + iconSize / 2, cy - (letterP.ascent() + letterP.descent()) / 2, letterP)
        }
        // точка состояния: выполняется / ждёт диска / остановлен
        val dot = when (pr.state) { 'R' -> Ui.OK; 'D' -> Ui.HOT; 'T', 't', 'Z' -> Ui.WARN; else -> 0 }
        if (dot != 0) {
            p.color = Ui.base
            c.drawCircle(ix + iconSize - dp(3f), cy + iconSize / 2 - dp(3f), dp(6f), p)
            p.color = dot
            c.drawCircle(ix + iconSize - dp(3f), cy + iconSize / 2 - dp(3f), dp(4f), p)
        }

        // справа: главная метрика
        val right = width - side - dp(14f)
        val (value, frac, color) = when (metric) {
            Metric.CPU -> Triple(Fmt.pct(pr.cpu) + "%", pr.cpu / 100f, Ui.load(pr.cpu, 10f, 50f))
            Metric.MEM -> Triple(Fmt.size(pr.rss), pr.rss / maxValue, Ui.load(pr.mem, 5f, 15f))
            Metric.TIME -> Triple(Fmt.cpuTime(pr.cpuTicks, clkTck), pr.cpuTicks / maxValue, Ui.primary)
        }
        valueP.color = if (metric == Metric.CPU && pr.cpu < 0.05f) Ui.TEXT3 else color
        c.drawText(value, right, cy - dp(2f), valueP)
        val bw = dp(48f)
        r.set(right - bw, cy + dp(8f), right, cy + dp(12f))
        p.color = Ui.TRACK
        c.drawRoundRect(r, dp(2f), dp(2f), p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.01f) {
            r.left = right - bw * f
            p.color = color
            c.drawRoundRect(r, dp(2f), dp(2f), p)
        }

        // текст
        val tx = ix + iconSize + dp(14f)
        val room = right - bw - dp(12f) - tx
        titleP.color = when {
            pr.pid == ownPid -> Ui.OK
            pr.kernel -> Ui.TEXT2
            else -> Ui.TEXT
        }
        c.drawText(Ui.ellipsize(titleP, pr.title, room), tx, cy - dp(3f), titleP)
        val second = when (metric) {
            Metric.CPU -> Fmt.size(pr.rss)
            else -> Fmt.pct(pr.cpu) + "% CPU"
        }
        val sub = ctx.getString(R.string.row_sub, pr.pid, pr.user.ifEmpty { "?" }, second)
        c.drawText(Ui.ellipsize(subP, sub, room), tx, cy + dp(15f), subP)
    }
}
