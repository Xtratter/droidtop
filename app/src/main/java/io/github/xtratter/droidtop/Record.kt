package io.github.xtratter.droidtop

import java.util.Locale

/** Запись статистики приложения в CSV: что пишем и как считаем итоги. */
object Record {
    /** Кого пишем: все процессы пакета или, если это не приложение, процессы с этим именем. */
    class Target(val pkg: String?, val name: String, val label: String) {
        fun matches(p: ProcInfo) = if (pkg != null) p.pkg == pkg else p.name == name
    }

    const val HEADER = "time,elapsed_s,app_cpu_pct,app_rss_mb,app_procs,app_threads," +
        "sys_cpu_pct,cpu_temp_c,mem_used_mb,swap_used_mb,gpu_pct,gpu_mhz," +
        "battery_pct,battery_w,charging,battery_temp_c,screen_on"

    /** Сумма по процессам приложения в одном замере. */
    class Sample(val cpu: Float, val rss: Long, val procs: Int, val threads: Int)

    fun sample(t: Target, s: Snapshot): Sample {
        var cpu = 0f; var rss = 0L; var n = 0; var thr = 0
        for (p in s.procs) if (t.matches(p)) { cpu += p.cpu; rss += p.rss; n++; thr += p.threads }
        return Sample(cpu, rss, n, thr)
    }

    private fun f1(v: Float) = if (v.isNaN()) "" else String.format(Locale.ROOT, "%.1f", v)
    private fun f2(v: Float) = if (v.isNaN()) "" else String.format(Locale.ROOT, "%.2f", v)
    private fun mb(b: Long) = String.format(Locale.ROOT, "%.1f", b / 1048576.0)

    /** Строка CSV; пустое поле — значение недоступно (например, загрузка CPU без root). */
    fun row(time: String, elapsedSec: Double, a: Sample, s: Snapshot, screenOn: Boolean): String {
        val g = s.gpu
        val b = s.battery
        return listOf(
            time, String.format(Locale.ROOT, "%.1f", elapsedSec),
            f1(a.cpu), mb(a.rss), a.procs.toString(), a.threads.toString(),
            f1(s.cpu), f1(s.cpuTemp), mb(s.memTotal - s.memAvail), mb(s.swapTotal - s.swapFree),
            g?.let { f1(it.busy) } ?: "", g?.freqMHz?.takeIf { it > 0 }?.toString() ?: "",
            b?.level?.toString() ?: "", b?.let { f2(it.powerW) } ?: "", b?.let { if (it.charging) "1" else "0" } ?: "",
            b?.let { f1(it.temp) } ?: "", if (screenOn) "1" else "0",
        ).joinToString(",")
    }

    /** Итоги записи: средние и максимумы, пока приложение было запущено. */
    class Stats {
        var samples = 0; private set
        var running = 0; private set
        private var cpuSum = 0.0
        var cpuMax = 0f; private set
        private var rssSum = 0.0
        var rssMax = 0L; private set

        fun add(a: Sample) {
            samples++
            if (a.procs == 0) return
            running++
            cpuSum += a.cpu; cpuMax = maxOf(cpuMax, a.cpu)
            rssSum += a.rss; rssMax = maxOf(rssMax, a.rss)
        }

        val cpuAvg get() = if (running > 0) (cpuSum / running).toFloat() else 0f
        val rssAvg get() = if (running > 0) (rssSum / running).toLong() else 0L
    }

    /** Имя файла без символов, которые не любят файловые системы. */
    fun fileName(label: String, stamp: String) =
        "DroidTop_" + label.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').take(40) + "_" + stamp + ".csv"
}
