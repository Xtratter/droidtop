package io.github.xtratter.droidtop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
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
    private val step = dp(18f)
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59FFFFFF; strokeWidth = dp(1.5f); strokeCap = Paint.Cap.ROUND
    }
    private val badgeP = Ui.textPaint(ctx, 11f, Ui.bold).apply { textAlign = Paint.Align.CENTER }
    private var glassLeft = -1

    var row: Row? = null
    /** Где пальцем нажали в последний раз — чтобы нажатие по значку сворачивало ветку. */
    var lastDownX = 0f; private set
    /** Правая граница значка: левее — зона сворачивания ветки. */
    val iconRight get() = cardLeft() + dp(12f) + iconSize + dp(6f)

    private fun indentLevels() = minOf(row?.depth ?: 0, 7)
    private fun cardLeft() = side + indentLevels() * step

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) lastDownX = e.x
        return super.dispatchTouchEvent(e)
    }
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
        glassLeft = -1
    }

    override fun onDraw(c: Canvas) {
        val rw = row ?: return
        val pr = rw.p
        val left = cardLeft()
        if (glassLeft != left.toInt()) {
            glassLeft = left.toInt()
            glass.setBounds(glassLeft, vgap.toInt(), (width - side).toInt(), (height - vgap).toInt())
        }
        drawGuides(c, rw)
        glass.draw(c)
        val ctx = context
        val cy = height / 2f
        val ix = left + dp(12f)

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

        // у ветки дерева — значок: «+N» у свёрнутой, «▾» у раскрытой
        if (rw.cont != null && rw.kids > 0) {
            val text = if (rw.collapsed) "+" + rw.descendants else "▾"
            val bw = maxOf(dp(20f), badgeP.measureText(text) + dp(10f))
            r.set(ix - dp(4f), cy + iconSize / 2 - dp(14f), ix - dp(4f) + bw, cy + iconSize / 2 + dp(4f))
            p.color = Ui.base
            c.drawRoundRect(r, dp(9f), dp(9f), p)
            r.inset(dp(1.5f), dp(1.5f))
            p.color = if (rw.collapsed) Ui.tertiary else 0xFF3A3D46.toInt()
            c.drawRoundRect(r, dp(8f), dp(8f), p)
            badgeP.color = if (rw.collapsed) Ui.ON_ACCENT else Ui.TEXT
            c.drawText(text, r.centerX(), r.centerY() - (badgeP.ascent() + badgeP.descent()) / 2, badgeP)
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

    /** Линии дерева в отступе слева: вертикали предков и «уголок» к своей карточке. */
    private fun drawGuides(c: Canvas, rw: Row) {
        val cont = rw.cont ?: return
        val d = indentLevels()
        if (d == 0) return
        val h = height.toFloat()
        val cy = h / 2
        fun x(k: Int) = side + (k - 1) * step + step / 2
        for (k in 1 until d) if (cont.getOrElse(k) { false }) c.drawLine(x(k), 0f, x(k), h, lineP)
        val last = !cont.getOrElse(rw.depth) { false }
        c.drawLine(x(d), 0f, x(d), if (last) cy else h, lineP)
        c.drawLine(x(d), cy, side + d * step, cy, lineP)
    }
}
