package io.github.xtratter.droidtop

/** Кольцевой буфер значений для графика: старые → новые. */
class Series(val capacity: Int = History.SIZE) {
    private val data = FloatArray(capacity)
    private var head = 0
    var size = 0; private set

    fun add(v: Float) {
        data[head] = v
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    operator fun get(i: Int): Float = data[(head - size + i + capacity) % capacity]

    fun toArray() = FloatArray(size) { get(it) }
}

/**
 * История замеров для графиков: общая загрузка, память, мощность батареи и каждый процесс.
 * Пишется в главном потоке прямо перед рассылкой замера.
 */
object History {
    /** Сколько точек хранится для общих графиков (при замере раз в 2 с — 20 минут). */
    const val SIZE = 600
    /** Для каждого процесса храним меньше, чтобы не тратить память на сотни процессов. */
    const val PROC_SIZE = 150

    val cpu = Series()
    val mem = Series()
    val power = Series()
    val gpu = Series()
    /** Сколько секунд в среднем между точками — для подписи «за N мин». */
    var secondsPerPoint = 2f; private set
    private var lastUptime = -1.0

    class Proc {
        val cpu = Series(PROC_SIZE)
        val rss = Series(PROC_SIZE)
    }

    private var procs = HashMap<Long, Proc>()

    private fun key(p: ProcInfo) = (p.startTicks shl 23) xor p.pid.toLong()

    fun proc(p: ProcInfo): Proc? = procs[key(p)]

    fun add(s: Snapshot) {
        if (lastUptime >= 0 && s.uptime > lastUptime) {
            // в среднее берём только обычные промежутки: пауза (экран выключен, приложение свёрнуто),
            // быстрый второй замер и обновление по кнопке исказили бы подпись «за N мин»
            val dt = (s.uptime - lastUptime).toFloat()
            if (dt in s.interval * 0.5f..s.interval * 2f) secondsPerPoint = secondsPerPoint * 0.8f + dt * 0.2f
        } else if (lastUptime < 0) {
            secondsPerPoint = s.interval
        }
        lastUptime = s.uptime
        cpu.add(if (!s.cpu.isNaN()) s.cpu else {
            // без доступа к /proc/stat — средняя частота ядер относительно максимальной
            val c = s.cores.filter { it.maxKHz > 0 }
            if (c.isEmpty()) 0f else c.sumOf { (it.freqKHz * 100.0 / it.maxKHz) }.toFloat() / c.size
        })
        mem.add(if (s.memTotal > 0) (s.memTotal - s.memAvail) * 100f / s.memTotal else 0f)
        power.add(s.battery?.powerW ?: 0f)
        gpu.add((s.gpu?.fraction ?: 0f) * 100f)

        val next = HashMap<Long, Proc>(s.procs.size * 2)
        for (p in s.procs) {
            val k = key(p)
            val h = procs[k] ?: Proc()
            h.cpu.add(p.cpu)
            h.rss.add(p.rss / 1048576f)
            next[k] = h
        }
        procs = next
    }

    /** Сброс, когда меняется источник данных (например, включили root). */
    fun clearProcs() { procs.clear() }
}
