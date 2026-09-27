package io.github.xtratter.droidtop

import java.util.Locale

/** Компактное форматирование чисел для узких колонок. */
object Fmt {
    private fun f(pattern: String, vararg args: Any) = String.format(Locale.ROOT, pattern, *args)

    /** Размер в байтах: 512K, 123M, 1.23G, 12.3G. */
    fun size(bytes: Long): String {
        val k = bytes / 1024.0
        val m = k / 1024
        val g = m / 1024
        return when {
            k < 1000 -> f("%.0fK", k)
            m < 1000 -> f("%.0fM", m)
            g < 10 -> f("%.2fG", g)
            else -> f("%.1fG", g)
        }
    }

    fun pct(v: Float): String = if (v.isNaN()) "-" else if (v >= 99.95f) f("%.0f", v) else f("%.1f", v)

    /** Время CPU как в htop: м:сс.сс или ч:мм:сс. */
    fun cpuTime(ticks: Long, clkTck: Long): String {
        val cs = ticks * 100 / clkTck.coerceAtLeast(1)
        val s = cs / 100
        return if (s < 3600) f("%d:%02d.%02d", s / 60, s % 60, cs % 100)
        else f("%dh%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    /** Частота из кГц: 1.80G или 806M. */
    fun freq(khz: Long): String = if (khz >= 1_000_000) f("%.2fG", khz / 1e6) else f("%dM", khz / 1000)

    fun hms(sec: Long): String = f("%02d:%02d:%02d", sec / 3600 % 24, sec / 60 % 60, sec % 60)

    fun temp(t: Float): String = f("%.0f°C", t)

    fun load(v: Float): String = f("%.2f", v)

    fun watts(v: Float): String = if (v < 10f) f("%.2f W", v) else f("%.1f W", v)
}

/** Размеры и частоты с единицами на языке интерфейса — для карточек. */
object Human {
    fun size(ctx: android.content.Context, bytes: Long): String {
        val m = bytes / 1048576.0
        return if (m < 1000) String.format(Locale.getDefault(), "%.0f %s", m, ctx.getString(R.string.u_mb))
        else String.format(Locale.getDefault(), "%.2f %s", m / 1024, ctx.getString(R.string.u_gb))
    }

    /** «40 с» или «3 мин» назад. */
    fun ago(ctx: android.content.Context, sec: Int): String =
        if (sec < 60) ctx.getString(R.string.dur_s, sec.toLong()) else ctx.getString(R.string.dur_m, (sec / 60).toLong())

    fun ghz(ctx: android.content.Context, khz: Long) =
        String.format(Locale.getDefault(), "%.2f %s", khz / 1e6, ctx.getString(R.string.u_ghz))
}

object HumanTime {
    fun duration(ctx: android.content.Context, minutes: Int): String =
        if (minutes < 60) ctx.getString(R.string.dur_m, minutes.toLong())
        else ctx.getString(R.string.dur_hm, (minutes / 60).toLong(), (minutes % 60).toLong())
}
