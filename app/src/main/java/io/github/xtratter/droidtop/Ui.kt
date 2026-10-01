package io.github.xtratter.droidtop

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.OvershootInterpolator
import io.github.xtratter.uikit.M3
import io.github.xtratter.uikit.M3Background
import io.github.xtratter.uikit.M3Dialog
import io.github.xtratter.uikit.M3Surface

/**
 * Цвета, шрифты и оформление DroidTop. Сам Material 3 Expressive (схема цветов, поверхности, окна) — в общем
 * наборе android-ui-kit ([M3]), как в AppShelf; здесь — темы DroidTop (с классическим htop) и короткие имена.
 */
object Ui {
    const val EXPRESSIVE = true

    val primary get() = M3.primary
    val secondary get() = M3.secondary
    val tertiary get() = M3.tertiary
    val base get() = M3.base
    val light get() = M3.light
    val aurora get() = M3.aurora
    val auroraColors get() = M3.auroraColors
    val auroraStrength get() = M3.auroraStrength
    val surfaceContainer get() = M3.surfaceContainer
    val surfaceContainerHigh get() = M3.surfaceContainerHigh
    val TEXT get() = M3.TEXT
    val TEXT2 get() = M3.TEXT2
    val TEXT3 get() = M3.TEXT3
    val ON_ACCENT get() = M3.ON_ACCENT
    val WARN get() = M3.WARN
    val HOT get() = M3.HOT
    val OK get() = M3.OK
    val TRACK get() = M3.TRACK
    val card get() = M3.card
    val dialogBlur get() = M3.dialogBlur
    val dialogSolid get() = M3.dialogSolid
    val surface get() = M3.surface
    val hintFill get() = M3.hintFill
    val hintText get() = M3.hintText
    fun ink(alpha: Int) = M3.ink(alpha)

    val medium: Typeface get() = M3.medium
    val bold: Typeface get() = M3.bold
    val regular: Typeface get() = M3.regular

    /** Классический htop: зелёный акцент на чёрном — своя тема DroidTop поверх тёмной. */
    private val HTOP = M3.Custom(0xFF66BB6A.toInt(), 0xFF4FC3F7.toInt(), 0xFFCE93D8.toInt(), 0xFF000000.toInt(),
        0xFF0A0D0A.toInt(), 0x0FFFFFFF, 0xE6050805.toInt(), 0xFA050805.toInt())

    /** Какая тема применена сейчас (null — ещё никакая). */
    var theme: Theme? = null; private set

    private fun mode(t: Theme) = when (t) {
        Theme.SYSTEM -> M3.Mode.SYSTEM
        Theme.LIGHT -> M3.Mode.LIGHT
        Theme.GRAPHITE -> M3.Mode.GRAPHITE
        Theme.AMOLED -> M3.Mode.AMOLED
        Theme.STANDARD, Theme.HTOP -> M3.Mode.DARK
    }

    private fun custom(t: Theme) = if (t == Theme.HTOP) HTOP else null

    fun init(ctx: Context) {
        if (theme == null) apply(ctx, Prefs(ctx).theme())
    }

    /** Тема [t] с учётом системного тёмного режима (для [Theme.SYSTEM]). */
    fun resolve(ctx: Context, t: Theme): Theme = if (t == Theme.HTOP) t else when (M3.resolve(ctx, mode(t))) {
        M3.Mode.LIGHT -> Theme.LIGHT
        M3.Mode.GRAPHITE -> Theme.GRAPHITE
        M3.Mode.AMOLED -> Theme.AMOLED
        else -> Theme.STANDARD
    }

    fun isCurrent(ctx: Context, t: Theme) = theme == t && M3.isCurrent(ctx, mode(t), Prefs(ctx).translucent, custom(t))

    fun apply(ctx: Context, t: Theme) {
        theme = t
        M3.apply(ctx, mode(t), Prefs(ctx).translucent, custom(t))
        Palette.apply(light)
    }

    /** Цвет по нагрузке: спокойно — акцент, заметно — янтарь, много — красный. */
    fun load(v: Float, warn: Float = 50f, high: Float = 85f) = when {
        v >= high -> HOT
        v >= warn -> WARN
        else -> primary
    }

    fun withAlpha(color: Int, a: Float) = M3.withAlpha(color, a)
    fun mix(a: Int, b: Int, t: Float) = M3.mix(a, b, t)
    fun dp(ctx: Context, v: Float) = M3.dp(ctx, v)
    fun sp(ctx: Context, v: Float) = M3.sp(ctx, v)
    fun textPaint(ctx: Context, sizeSp: Float, face: Typeface = regular, color: Int = TEXT) = M3.textPaint(ctx, sizeSp, face, color)
    fun ellipsize(p: Paint, s: String, width: Float) = M3.ellipsize(p, s, width)
    fun ripple(ctx: Context, radiusDp: Float, insetH: Float = 0f, insetV: Float = 0f) = M3.ripple(ctx, radiusDp, insetH, insetV)
    fun pill(ctx: Context, fill: Int, stroke: Int = 0, radiusDp: Float = 100f) = M3.pill(ctx, fill, stroke, radiusDp)

    val openDialogs get() = M3Dialog.count
    fun forgetDialogs() = M3Dialog.forget()
    var onDialogsClosed: (() -> Unit)?
        get() = M3Dialog.onAllClosed
        set(v) { M3Dialog.onAllClosed = v }

    /** Оформить окно: тональный фон, размытие экрана позади (Android 12+), щелчки, мягкие края. */
    fun glassDialog(d: AlertDialog) {
        M3Dialog.edgeDp = 40f
        M3Dialog.style(d)
    }
}

/** Поверхность карточек, панели и окон — из набора ([M3Surface]). */
typealias GlassDrawable = M3Surface
/** Фон окна с мягкими цветными пятнами — из набора ([M3Background]). */
typealias AuroraDrawable = M3Background

/**
 * Вертикальный столбик-«пилюля»: дорожка со скруглением и заливка снизу, обрезанная по форме дорожки.
 * Так даже 3 % выглядят как 3 %, а не как кружок.
 */
object PillBar {
    private val path = android.graphics.Path()
    private val rect = RectF()

    fun draw(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float,
             frac: Float, track: Paint, fill: Paint) {
        rect.set(left, top, right, bottom)
        c.drawRoundRect(rect, radius, radius, track)
        val f = frac.coerceIn(0f, 1f)
        if (f <= 0.004f) return
        path.reset()
        path.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
        c.save()
        c.clipPath(path)
        c.drawRect(left, bottom - (bottom - top) * f, right, bottom, fill)
        c.restore()
    }
}

/**
 * Плавный «пружинистый» переход между значениями (для полосок и больших чисел).
 * [animate] = false — значения меняются сразу, одним кадром.
 */
class Smooth(private val view: View, private val animate: Boolean = true) {
    var cur = FloatArray(0); private set
    private var from = FloatArray(0)
    private var to = FloatArray(0)
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 380
        interpolator = OvershootInterpolator(0.7f)
        addUpdateListener { a ->
            val t = a.animatedValue as Float
            for (i in cur.indices) cur[i] = from[i] + (to[i] - from[i]) * t
            view.invalidate()
        }
    }

    fun set(values: FloatArray) {
        if (!animate || values.size != cur.size || !view.isAttachedToWindow) {
            anim.cancel()
            cur = values.copyOf(); from = values.copyOf(); to = values.copyOf()
            view.invalidate()
            return
        }
        anim.cancel()
        from = cur.copyOf()
        to = values.copyOf()
        anim.start()
    }
}
