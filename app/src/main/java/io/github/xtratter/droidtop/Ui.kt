package io.github.xtratter.droidtop

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator

/** Цвета, шрифты и «стеклянные» элементы оформления. */
object Ui {
    // Material You: на Android 12+ берём цвета из обоев
    var primary = 0xFFA8C7FA.toInt(); private set
    var secondary = 0xFFBFC6DC.toInt(); private set
    var tertiary = 0xFFD7BAFF.toInt(); private set
    var base = 0xFF0D0F14.toInt(); private set

    const val TEXT = 0xFFF2F2F6.toInt()
    const val TEXT2 = 0xB3F2F2F6.toInt()
    const val TEXT3 = 0x73F2F2F6
    const val ON_ACCENT = 0xFF10131A.toInt()
    const val WARN = 0xFFFFC857.toInt()
    const val HOT = 0xFFFF6B6B.toInt()
    const val OK = 0xFF7EE08A.toInt()
    const val TRACK = 0x1AFFFFFF

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 700, false) else Typeface.DEFAULT_BOLD
    val regular: Typeface = Typeface.DEFAULT

    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        if (Build.VERSION.SDK_INT >= 31) {
            primary = ctx.getColor(android.R.color.system_accent1_200)
            secondary = ctx.getColor(android.R.color.system_accent2_200)
            tertiary = ctx.getColor(android.R.color.system_accent3_200)
            base = mix(ctx.getColor(android.R.color.system_neutral1_900), 0xFF000000.toInt(), 0.35f)
        }
    }

    /** Цвет по нагрузке: спокойно — акцент, заметно — янтарь, много — красный. */
    fun load(v: Float, warn: Float = 50f, high: Float = 85f) = when {
        v >= high -> HOT
        v >= warn -> WARN
        else -> primary
    }

    fun withAlpha(color: Int, a: Float) = (color and 0xFFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density
    fun sp(ctx: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    fun textPaint(ctx: Context, sizeSp: Float, face: Typeface = regular, color: Int = TEXT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(ctx, sizeSp)
            typeface = face
            this.color = color
            fontFeatureSettings = "tnum"   // цифры одинаковой ширины — числа не «прыгают»
        }

    /** Обрезать строку с многоточием под ширину. */
    fun ellipsize(p: Paint, s: String, width: Float): String {
        if (p.measureText(s) <= width) return s
        val n = p.breakText(s, true, (width - p.measureText("…")).coerceAtLeast(0f), null)
        return s.take(n) + "…"
    }

    /** Эффект нажатия со скруглением. */
    fun ripple(ctx: Context, radiusDp: Float, insetH: Float = 0f, insetV: Float = 0f): Drawable {
        val mask = GradientDrawable().apply { cornerRadius = dp(ctx, radiusDp); setColor(-1) }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
            InsetDrawable(mask, insetH.toInt(), insetV.toInt(), insetH.toInt(), insetV.toInt()))
    }

    fun pill(ctx: Context, fill: Int, stroke: Int = 0, radiusDp: Float = 100f) = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp)
        setColor(fill)
        if (stroke != 0) setStroke(dp(ctx, 1f).toInt().coerceAtLeast(1), stroke)
    }

    /** Сколько «стеклянных» диалогов сейчас открыто. */
    var openDialogs = 0; private set
    /** Вызывается, когда закрылся последний диалог. */
    var onDialogsClosed: (() -> Unit)? = null

    /** Оформить диалог стеклом; на Android 12+ ещё и размыть то, что под ним. */
    fun glassDialog(d: AlertDialog) {
        val w = d.window ?: return
        // под размытым диалогом главный экран не обновляем: каждое его изменение заставляет
        // систему заново размывать весь экран, а под стеклом всё равно ничего не разобрать
        openDialogs++
        w.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                if (--openDialogs == 0) onDialogsClosed?.invoke()
            }
        })
        val ctx = d.context
        val blur = Build.VERSION.SDK_INT >= 31 &&
            ctx.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        w.setBackgroundDrawable(GlassDrawable(ctx, 28f, if (blur) 0x9E16181E.toInt() else 0xF016181E.toInt()))
        if (blur && Build.VERSION.SDK_INT >= 31) {
            w.setBackgroundBlurRadius(dp(ctx, 40f).toInt())
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            w.attributes = w.attributes.apply { blurBehindRadius = dp(ctx, 10f).toInt() }
        }
        w.setDimAmount(0.35f)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { d.getButton(it)?.setTextColor(primary) }
    }
}

/**
 * «Жидкое стекло»: полупрозрачная заливка, мягкий блик сверху и светлая кромка,
 * которая ярче в верхнем левом углу — как свет на гранях стекла.
 */
class GlassDrawable(ctx: Context, radiusDp: Float, private val fill: Int = 0x14FFFFFF) : Drawable() {
    private val radius = Ui.dp(ctx, radiusDp)
    private val d = ctx.resources.displayMetrics.density
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val hiP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d }
    private val r = RectF()

    override fun onBoundsChange(b: Rect) {
        r.set(b)
        r.inset(d / 2, d / 2)
        hiP.shader = LinearGradient(0f, r.top, 0f, r.top + minOf(r.height(), 90 * d),
            0x16FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        edgeP.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
            intArrayOf(0x70FFFFFF, 0x12FFFFFF, 0x12FFFFFF, 0x3DFFFFFF), floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        c.drawRoundRect(r, radius, radius, fillP)
        c.drawRoundRect(r, radius, radius, hiP)
        c.drawRoundRect(r, radius, radius, edgeP)
    }

    override fun getOutline(outline: Outline) = outline.setRoundRect(bounds, radius)
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Фон окна: тёмная основа и размытые цветные пятна из палитры обоев — их «преломляет» стекло. */
class AuroraDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobs = ArrayList<Triple<Float, Float, Float>>()
    private val shaders = ArrayList<Shader>()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var bmp: android.graphics.Bitmap? = null

    override fun onBoundsChange(b: Rect) {
        if (b.isEmpty) return
        // пятна мягкие, поэтому рисуем их один раз в картинку в 1/4 размера и потом только растягиваем:
        // так фон почти ничего не стоит при каждом кадре
        val w = b.width() / 4f
        val h = b.height() / 4f
        blobs.clear(); shaders.clear()
        fun blob(x: Float, y: Float, r: Float, color: Int, a: Float) {
            blobs += Triple(x, y, r)
            shaders += RadialGradient(x, y, r, Ui.withAlpha(color, a), Ui.withAlpha(color, 0f), Shader.TileMode.CLAMP)
        }
        blob(w * 0.05f, h * 0.08f, w * 0.95f, Ui.primary, 0.42f)
        blob(w * 1.0f, h * 0.38f, w * 0.85f, Ui.tertiary, 0.30f)
        blob(w * 0.1f, h * 0.78f, w * 0.9f, Ui.secondary, 0.22f)
        blob(w * 0.9f, h * 1.02f, w * 0.7f, Ui.primary, 0.25f)
        val out = android.graphics.Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Ui.base)
        for (i in blobs.indices) {
            p.shader = shaders[i]
            c.drawCircle(blobs[i].first, blobs[i].second, blobs[i].third, p)
        }
        bmp = out
    }

    override fun draw(c: Canvas) {
        val b = bmp
        if (b == null) c.drawColor(Ui.base) else c.drawBitmap(b, null, bounds, bmpPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

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
