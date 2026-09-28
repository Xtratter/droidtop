package io.github.xtratter.droidtop

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Текстовая сводка текущего замера — для буфера обмена. */
object Summary {
    private const val TOP = 10

    fun text(ctx: Context, s: Snapshot, kernelThreads: Boolean): String {
        fun g(id: Int, vararg a: Any) = ctx.getString(id, *a)
        val sb = StringBuilder()
        sb.append("DroidTop · ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
            .append(" · ").append(android.os.Build.MODEL).append('\n')
        val cpu = if (s.cpu.isNaN()) "—" else Fmt.pct(s.cpu) + "%"
        sb.append(g(R.string.sum_cpu, cpu, if (s.cpuTemp.isNaN()) "—" else Fmt.temp(s.cpuTemp), s.cores.size)).append('\n')
        val cores = s.cores.joinToString(" ") { c ->
            if (!c.usage.isNaN()) Fmt.pct(c.usage) + "%" else if (c.freqKHz > 0) Fmt.freq(c.freqKHz) else "-"
        }
        sb.append(g(R.string.sum_cores, cores)).append('\n')
        val used = s.memTotal - s.memAvail
        sb.append(g(R.string.sum_mem, Human.size(ctx, used), Human.size(ctx, s.memTotal),
            Fmt.pct(used * 100f / s.memTotal.coerceAtLeast(1))))
        if (s.swapTotal > 0) sb.append(" · ").append(g(R.string.sum_swap, Human.size(ctx, s.swapTotal - s.swapFree), Human.size(ctx, s.swapTotal)))
        sb.append('\n')
        s.gpu?.let { gp ->
            val parts = listOfNotNull(gp.model, gp.busy.takeIf { !it.isNaN() }?.let { Fmt.pct(it) + "%" },
                gp.freqMHz.takeIf { it > 0 }?.let { "$it MHz" }, gp.temp.takeIf { !it.isNaN() }?.let { Fmt.temp(it) })
            sb.append("GPU: ").append(parts.joinToString(" · ")).append('\n')
        }
        s.battery?.let { b ->
            sb.append(g(R.string.sum_battery, b.level, Fmt.watts(b.powerW),
                g(if (b.charging) R.string.b_charging else R.string.b_discharging), Fmt.temp(b.temp))).append('\n')
        }
        s.load?.let { sb.append(g(R.string.sum_load, Fmt.load(it[0]), Fmt.load(it[1]), Fmt.load(it[2]))).append(" · ") }
        sb.append(g(R.string.sum_tasks, s.procs.size, s.threads)).append('\n')
        val top = s.procs.asSequence().filter { kernelThreads || !it.kernel }
            .sortedByDescending { it.cpu }.take(TOP).toList()
        if (top.isNotEmpty()) {
            sb.append('\n').append(g(R.string.sum_top, top.size)).append('\n')
            for (p in top) sb.append(String.format(Locale.ROOT, "%7d  %6s%%  %6s  %s", p.pid, Fmt.pct(p.cpu), Fmt.size(p.rss), p.title)).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
