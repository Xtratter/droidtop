package io.github.xtratter.droidtop

/** Одно ядро: загрузка в % (NaN — неизвестно), текущая и максимальная частота в кГц (0 — нет данных). */
class CoreInfo(val usage: Float, val freqKHz: Long, val maxKHz: Long)

class ProcInfo(
    val pid: Int,
    val ppid: Int,
    val comm: String,
    val state: Char,
    val cpuTicks: Long,
    val prio: Int,
    val nice: Int,
    val threads: Int,
    val startTicks: Long,
    val vsize: Long,
    val rss: Long,
) {
    var user = ""
    var name = comm
    /** Имя для показа: название приложения или имя процесса. */
    var title = comm
    /** Пакет Android, если процесс принадлежит установленному приложению. */
    var pkg: String? = null
    var label: String? = null
    var cpu = 0f
    var mem = 0f

    val kernel get() = pid == 2 || ppid == 2
}

class Snapshot(
    val access: Access,
    val cores: List<CoreInfo>,
    /** Общая загрузка CPU в %, NaN — недоступна без root. */
    val cpu: Float,
    val memTotal: Long,
    val memAvail: Long,
    val memFree: Long,
    val swapTotal: Long,
    val swapFree: Long,
    val load: FloatArray?,
    val uptime: Double,
    val procs: List<ProcInfo>,
    val cpuTemp: Float,
    val clkTck: Long,
    /** Сколько задач сейчас в очереди на CPU (из /proc/loadavg), -1 — неизвестно. */
    runningTasks: Int = -1,
    /** Видеочип; null — данных нет (обычно без root). */
    val gpu: Gpu? = null,
) {
    val root get() = access == Access.ROOT
    /** Видим все процессы и загрузку CPU (root или Shizuku). */
    val full get() = access != Access.USER
    var battery: Battery? = null
    val batteryTemp get() = battery?.temp ?: Float.NaN
    val threads = procs.sumOf { it.threads }
    val running = if (runningTasks >= 0) runningTasks else procs.count { it.state == 'R' }
}

enum class Sort(val cmp: Comparator<ProcInfo>, val ascByDefault: Boolean = false) {
    PID(compareBy { it.pid }, true),
    USER(compareBy(String.CASE_INSENSITIVE_ORDER) { it.user }, true),
    NI(compareBy { it.nice }),
    STATE(compareBy { it.state }, true),
    CPU(compareBy { it.cpu }),
    MEM(compareBy { it.rss }),
    TIME(compareBy { it.cpuTicks }),
    THR(compareBy { it.threads }),
    NAME(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }, true);

    fun comparator(asc: Boolean): Comparator<ProcInfo> {
        val c = if (asc) cmp else cmp.reversed()
        return c.thenBy { it.pid }
    }
}
