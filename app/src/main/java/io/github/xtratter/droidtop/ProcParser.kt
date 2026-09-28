package io.github.xtratter.droidtop

/**
 * Собирает скрипт для оболочки и разбирает его вывод.
 * Хранит прошлые значения счётчиков, чтобы считать загрузку CPU между замерами.
 */
class ProcParser(private val pageSize: Long, private val clkTck: Long) {
    private var prevCpu = HashMap<String, LongArray>()      // "cpu", "cpu0"… → [всего, простой]
    private var prevProc = HashMap<Int, LongArray>()        // pid → [время старта, тики CPU]
    private var prevUptime = -1.0
    private val names = HashMap<Int, Name>()                 // pid → имя и пользователь из ps
    private var thermal: List<String>? = null               // файлы температуры CPU и GPU; null — ещё не искали
    private var gpuThermal = emptySet<String>()             // какие из них — датчики GPU
    private var gpuPaths: List<String>? = null              // файлы GPU, которые удалось прочитать; null — ещё не искали
    private val maxFreq = HashMap<Int, Long>()
    private var maxCore = Runtime.getRuntime().availableProcessors() - 1

    private class Name(val start: Long, val user: String, val name: String)

    /** Есть ли прошлый замер (без него загрузку CPU не посчитать). */
    val primed get() = prevUptime >= 0

    /**
     * Одна команда на замер: один `grep` читает все файлы и подписывает каждую строку путём
     * («/proc/stat:cpu …»), поэтому новых процессов за такт запускается ровно один.
     * Строку `intr` из /proc/stat (тысячи чисел) пропускаем — она не нужна.
     */
    fun script(): String = buildString {
        append("grep -svH '^intr ' /proc/stat /proc/meminfo /proc/loadavg /proc/uptime")
        append(" /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq")
        if (maxFreq.isEmpty()) append(" /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq")
        val t = thermal
        if (t == null) append(" /sys/class/thermal/thermal_zone*/type")
        else for (p in t) append(' ').append(p)
        for (p in gpuPaths ?: Gpu.PATHS) append(' ').append(p)
        append(" /proc/[0-9]*/stat\n")
    }

    fun parse(out: String, access: Access, uptimeFallback: Double, memFallback: LongArray) =
        parse(out.split('\n'), access, uptimeFallback, memFallback)

    /**
     * Разобрать вывод [script] — строки «путь:содержимое».
     * [memFallback] — {всего, доступно} от ActivityManager на случай, если /proc/meminfo закрыт.
     */
    fun parse(lines: List<String>, access: Access, uptimeFallback: Double, memFallback: LongArray): Snapshot {
        val stat = ArrayList<String>()
        val mem = ArrayList<String>()
        var loadLine: String? = null
        var uptimeLine: String? = null
        val freqLines = ArrayList<String>()
        val maxLines = ArrayList<String>()
        val zoneLines = ArrayList<String>()
        val tempLines = ArrayList<String>()
        val gpuLines = ArrayList<String>()
        val procLines = ArrayList<String>()
        val gpuSet = gpuPaths ?: Gpu.PATHS
        for (l in lines) {
            val c = l.indexOf(':')
            if (c <= 0 || c == l.length - 1) continue
            when {
                isProcStat(l, c) -> procLines += l
                l.startsWith("/proc/stat:") -> stat += l.substring(c + 1)
                l.startsWith("/proc/meminfo:") -> mem += l.substring(c + 1)
                l.startsWith("/proc/loadavg:") -> loadLine = l.substring(c + 1)
                l.startsWith("/proc/uptime:") -> uptimeLine = l.substring(c + 1)
                l.startsWith("/sys/devices/system/cpu/") ->
                    if (l.regionMatches(c - 16, "scaling_cur_freq", 0, 16)) freqLines += l else maxLines += l
                l.startsWith("/sys/class/thermal/") ->
                    if (l.regionMatches(c - 5, "/type", 0, 5)) zoneLines += l else tempLines += l
                else -> if (l.substring(0, c) in gpuSet) gpuLines += l
            }
        }

        val uptime = uptimeLine?.substringBefore(' ')?.toDoubleOrNull() ?: uptimeFallback

        // CPU: /proc/stat
        val cpuNow = HashMap<String, LongArray>()
        for (l in stat) {
            if (!l.startsWith("cpu")) continue
            val p = l.split(' ').filter { it.isNotEmpty() }
            val v = p.drop(1).take(8).map { it.toLongOrNull() ?: 0L }
            if (v.size < 5) continue
            cpuNow[p[0]] = longArrayOf(v.sum(), v[3] + v[4])
            p[0].removePrefix("cpu").toIntOrNull()?.let { if (it > maxCore) maxCore = it }
        }
        fun usage(key: String): Float {
            val n = cpuNow[key] ?: return Float.NaN
            val o = prevCpu[key] ?: return Float.NaN
            val total = n[0] - o[0]
            if (total <= 0) return Float.NaN
            val busy = total - (n[1] - o[1])
            return (busy * 100f / total).coerceIn(0f, 100f)
        }

        val freq = coreValues(freqLines)
        if (maxFreq.isEmpty()) maxFreq.putAll(coreValues(maxLines))
        val cores = (0..maxCore).map { CoreInfo(usage("cpu$it"), freq[it] ?: 0L, maxFreq[it] ?: 0L) }
        val cpuTotal = usage("cpu")
        prevCpu = cpuNow

        // Память
        val mi = HashMap<String, Long>()
        for (l in mem) {
            val v = l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: continue
            mi[l.substringBefore(':')] = v * 1024
        }
        val memTotal = mi["MemTotal"] ?: memFallback[0]
        val memAvail = mi["MemAvailable"] ?: memFallback[1]
        val memFree = mi["MemFree"] ?: memAvail

        // 4-е поле loadavg — «выполняются/всего»; вычитаем 1 — это наш собственный grep
        val runningTasks = loadLine?.split(' ')?.getOrNull(3)?.substringBefore('/')?.toIntOrNull()
            ?.let { (it - 1).coerceAtLeast(0) } ?: -1
        val load = loadLine?.split(' ')?.take(3)?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 3 }?.toFloatArray()

        // Температура: при первом замере выбираем датчики по их типу
        if (thermal == null) pickZones(zoneLines)
        val (gpuT, cpuT) = tempLines.partition { it.substringBefore(':') in gpuThermal }
        val cpuTemp = maxTemp(cpuT)

        // GPU: при первом замере запоминаем, какие файлы читаются, дальше спрашиваем только их
        if (gpuPaths == null) gpuPaths = gpuLines.map { it.substringBefore(':') }.distinct()
        val gpu = Gpu.parse(gpuLines, maxTemp(gpuT))

        // Процессы
        val dt = if (prevUptime >= 0) uptime - prevUptime else 0.0
        val procNow = HashMap<Int, LongArray>()
        val procs = ArrayList<ProcInfo>()
        for (l in procLines) {
            val p = parseStat(l) ?: continue
            procNow[p.pid] = longArrayOf(p.startTicks, p.cpuTicks)
            val old = prevProc[p.pid]
            if (old != null && old[0] == p.startTicks && dt > 0)
                p.cpu = ((p.cpuTicks - old[1]) * 100.0 / (dt * clkTck)).toFloat().coerceAtLeast(0f)
            if (memTotal > 0) p.mem = p.rss * 100f / memTotal
            procs += p
        }
        prevProc = procNow
        prevUptime = uptime

        return Snapshot(
            access = access, cores = cores, cpu = cpuTotal,
            memTotal = memTotal, memAvail = memAvail, memFree = memFree,
            swapTotal = mi["SwapTotal"] ?: 0L, swapFree = mi["SwapFree"] ?: 0L,
            load = load, uptime = uptime, procs = procs,
            cpuTemp = cpuTemp, clkTck = clkTck, runningTasks = runningTasks, gpu = gpu,
        )
    }

    /** Процессы, для которых ещё не знаем имя и пользователя. */
    fun missingNames(procs: List<ProcInfo>): List<Int> =
        procs.filter { names[it.pid]?.start != it.startTicks }.map { it.pid }

    /** Команда `ps` для получения полных имён (в /proc/PID/stat имя обрезано до 15 символов). */
    fun psCommand(pids: List<Int>): String {
        val list = pids.joinToString(",")
        return "ps -w -o PID,USER,NAME -p $list || ps -o PID,USER,NAME -p $list"
    }

    /** Записать имена из вывода ps и подставить их в процессы. */
    fun applyNames(psOut: String?, procs: List<ProcInfo>, asked: List<Int>) {
        val got = HashMap<Int, Pair<String, String>>()
        psOut?.lineSequence()?.forEach { line ->
            val p = line.trim().split(Regex("\\s+"), limit = 3)
            val pid = p.getOrNull(0)?.toIntOrNull() ?: return@forEach
            got[pid] = p.getOrElse(1) { "" } to p.getOrElse(2) { "" }
        }
        val askedSet = asked.toHashSet()
        for (pr in procs) {
            if (pr.pid in askedSet) {
                val g = got[pr.pid]
                names[pr.pid] = Name(pr.startTicks, g?.first ?: "?", g?.second?.takeIf { it.isNotBlank() } ?: pr.comm)
            }
            names[pr.pid]?.let { pr.user = it.user; pr.name = it.name }
        }
        // забыть давно завершившиеся процессы
        if (names.size > procs.size * 2 + 64) {
            val alive = procs.map { it.pid }.toHashSet()
            names.keys.retainAll(alive)
        }
    }

    fun reset() {
        prevCpu.clear(); prevProc.clear(); prevUptime = -1.0; names.clear()
        gpuPaths = null     // с другим доступом могут открыться другие файлы
    }

    private fun coreValues(lines: List<String>): Map<Int, Long> {
        val m = HashMap<Int, Long>()
        for (l in lines) {
            val core = CORE_RE.find(l)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val v = l.substringAfterLast(':').trim().toLongOrNull() ?: continue
            m[core] = v
            if (core > maxCore) maxCore = core
        }
        return m
    }

    private fun maxTemp(lines: List<String>) = lines
        .mapNotNull { it.substringAfterLast(':').trim().toFloatOrNull() }
        .map { if (it >= 1000f) it / 1000f else it }
        .filter { it > 0f && it < 150f }
        .maxOrNull() ?: Float.NaN

    /** Из списка «путь/type:имя» выбрать датчики процессора и видеочипа. */
    private fun pickZones(lines: List<String>) {
        val gpu = pickGpuZones(lines)
        gpuThermal = gpu.toHashSet()
        thermal = pickCpuZones(lines) + gpu
    }

    private fun pickGpuZones(lines: List<String>): List<String> {
        val zones = lines.mapNotNull { l ->
            val path = l.substringBefore(':')
            val type = l.substringAfter(':').lowercase()
            if (!path.endsWith("/type") || "gpu" !in type || type.endsWith("-step") || "cool" in type) null
            else path.removeSuffix("/type") + "/temp" to type
        }
        return zones.filter { it.second.endsWith("usr") }.ifEmpty { zones }.map { it.first }.take(4)
    }

    private fun pickCpuZones(lines: List<String>): List<String> {
        val zones = lines.mapNotNull { l ->
            val path = l.substringBefore(':')
            val type = l.substringAfter(':').lowercase()
            if (!path.endsWith("/type")) return@mapNotNull null
            path.removeSuffix("/type") + "/temp" to type
        }
        val cpu = zones.filter { (_, t) -> "cpu" in t && !t.endsWith("-step") && "cool" !in t }
        val picked = cpu.filter { it.second.endsWith("usr") }.ifEmpty { cpu }
            .ifEmpty { zones.filter { (_, t) -> t == "soc" || "tsens" in t || t == "ap" } }
        return picked.map { it.first }.take(16)
    }

    /** «/proc/123/stat:…» — строка процесса (путь до двоеточия [c]). */
    private fun isProcStat(l: String, c: Int): Boolean {
        if (c < 12 || !l.startsWith("/proc/") || !l.regionMatches(c - 5, "/stat", 0, 5)) return false
        for (i in 6 until c - 5) if (l[i] !in '0'..'9') return false
        return true
    }

    /**
     * Разбор «/proc/PID/stat:PID (имя) S …» без split: идём по полям прямо в строке,
     * иначе на каждый процесс создавалось бы полсотни временных строк.
     */
    private fun parseStat(line: String): ProcInfo? {
        val start = line.indexOf(':') + 1
        val lp = line.indexOf('(', start)
        val rp = line.lastIndexOf(')')
        if (lp < 0 || rp < lp || rp + 3 >= line.length) return null
        var pid = 0
        for (i in start until lp) {
            val ch = line[i]
            if (ch in '0'..'9') pid = pid * 10 + (ch - '0') else if (ch != ' ') return null
        }
        // f[0] — поле 3 (state), значит поле N лежит в f[N - 3]; нужны поля до 24-го
        val f = LongArray(22)
        var i = rp + 4          // после «) S »
        var k = 1
        while (k < 22 && i < line.length) {
            var neg = false
            var v = 0L
            if (line[i] == '-') { neg = true; i++ }
            while (i < line.length && line[i] != ' ') {
                val ch = line[i]
                if (ch in '0'..'9') v = v * 10 + (ch - '0')
                i++
            }
            f[k++] = if (neg) -v else v
            i++
        }
        if (k < 22) return null
        return ProcInfo(
            pid = pid,
            ppid = f[1].toInt(),
            comm = line.substring(lp + 1, rp),
            state = line[rp + 2],
            cpuTicks = f[11] + f[12],
            prio = f[15].toInt(),
            nice = f[16].toInt(),
            threads = f[17].toInt(),
            startTicks = f[19],
            vsize = f[20],
            rss = f[21] * pageSize,
        )
    }

    private companion object {
        val CORE_RE = Regex("/cpu(\\d+)/")
    }
}
