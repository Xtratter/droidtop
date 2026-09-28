package io.github.xtratter.droidtop

/**
 * Видеочип: загрузка в % (NaN — неизвестно), текущая и максимальная частота в МГц (0 — нет данных),
 * модель (например «Adreno650v2») и температура (NaN — нет датчика).
 * Файлы в /sys обычно закрыты для приложений, поэтому данные чаще всего есть только с root или Shizuku.
 */
class Gpu(val busy: Float, val freqMHz: Int, val maxMHz: Int, val model: String?, val temp: Float) {
    /** Загрузка в 0…1, а без неё — частота относительно максимальной. */
    val fraction: Float
        get() = when {
            !busy.isNaN() -> busy / 100f
            maxMHz > 0 && freqMHz > 0 -> freqMHz.toFloat() / maxMHz
            else -> 0f
        }

    companion object {
        /** Файлы, которые пробуем прочитать: Adreno (kgsl) и Mali / Google Tensor (/sys/kernel/gpu). */
        val PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/kernel/gpu/gpu_clock",
            "/sys/kernel/gpu/gpu_max_clock",
            "/sys/kernel/gpu/gpu_model",
        )

        /**
         * Разобрать строки «путь:значение» (вывод `grep -sH ''`).
         * [temp] — температура из датчиков GPU. Возвращает null, если ничего полезного не нашлось.
         */
        fun parse(lines: List<String>, temp: Float): Gpu? {
            val v = HashMap<String, String>()
            for (l in lines) {
                val i = l.indexOf(':')
                if (i > 0) v[l.substring(0, i).substringAfterLast("/sys/")] = l.substring(i + 1).trim()
            }
            fun num(key: String) = v[key]?.substringBefore(' ')?.substringBefore('%')?.toLongOrNull()

            var busy = (num("class/kgsl/kgsl-3d0/gpu_busy_percentage") ?: num("kernel/gpu/gpu_busy"))
                ?.toFloat() ?: Float.NaN
            if (busy.isNaN()) {
                // gpubusy — «занято всего» в микросекундах за последнее окно
                val p = v["class/kgsl/kgsl-3d0/gpubusy"]?.split(' ')?.filter { it.isNotEmpty() }
                val b = p?.getOrNull(0)?.toLongOrNull()
                val t = p?.getOrNull(1)?.toLongOrNull()
                if (b != null && t != null && t > 0) busy = b * 100f / t
            }
            if (!busy.isNaN()) busy = busy.coerceIn(0f, 100f)

            val freq = mhz(num("class/kgsl/kgsl-3d0/devfreq/cur_freq") ?: num("class/kgsl/kgsl-3d0/gpuclk")
                ?: num("kernel/gpu/gpu_clock"))
            val max = mhz(num("class/kgsl/kgsl-3d0/devfreq/max_freq") ?: num("class/kgsl/kgsl-3d0/max_gpuclk")
                ?: num("kernel/gpu/gpu_max_clock"))
            val model = (v["class/kgsl/kgsl-3d0/gpu_model"] ?: v["kernel/gpu/gpu_model"])
                ?.takeIf { it.isNotBlank() && it != "unknown" }

            if (busy.isNaN() && freq == 0 && temp.isNaN()) return null
            return Gpu(busy, freq, max, model?.let(::prettyModel), temp)
        }

        /** Частота бывает в Гц (kgsl), кГц или МГц (Mali) — приводим к МГц. */
        fun mhz(v: Long?): Int = when {
            v == null || v <= 0 -> 0
            v >= 10_000_000 -> (v / 1_000_000).toInt()
            v >= 10_000 -> (v / 1000).toInt()
            else -> v.toInt()
        }

        /** «Adreno650v2» → «Adreno 650». */
        fun prettyModel(m: String): String {
            val r = Regex("^(Adreno)\\s*(\\d+)(v\\d+)?$", RegexOption.IGNORE_CASE).find(m.trim())
            return if (r != null) "Adreno " + r.groupValues[2] else m.trim()
        }
    }
}
