package io.github.xtratter.droidtop

/** Потоки и открытые файлы одного процесса: команды для оболочки и разбор их вывода. */
object Inspect {
    class Thread(
        val tid: Int,
        val name: String,
        val state: Char,
        val ticks: Long,
        val nice: Int,
        /** Ядро, на котором поток выполнялся последним. */
        val core: Int,
    ) {
        /** % одного ядра за прошлый интервал, как у процессов; NaN — ещё не с чем сравнить. */
        var cpu = Float.NaN
    }

    enum class Kind { FILE, DEVICE, SOCKET, PIPE, OTHER }

    class Fd(val fd: Int, val target: String, val kind: Kind) {
        /** Для сокета — протокол и адреса, если нашли его в /proc/net. */
        var socket: String? = null
    }

    fun threadsCommand(pid: Int) = "cat /proc/$pid/task/*/stat 2>/dev/null"

    fun parseThreads(out: String): List<Thread> = out.lineSequence().mapNotNull { parseThread(it) }.toList()

    /** Строка /proc/PID/task/TID/stat; имя потока может содержать пробелы и скобки. */
    fun parseThread(line: String): Thread? {
        val lp = line.indexOf('(')
        val rp = line.lastIndexOf(')')
        if (lp < 1 || rp < lp) return null
        val tid = line.substring(0, lp).trim().toIntOrNull() ?: return null
        // t[0] — поле 3 (state), значит поле N лежит в t[N - 3]
        val t = line.substring(rp + 2).split(' ')
        if (t.size < 37) return null
        return Thread(
            tid = tid,
            name = line.substring(lp + 1, rp),
            state = t[0].firstOrNull() ?: '?',
            ticks = (t[11].toLongOrNull() ?: 0L) + (t[12].toLongOrNull() ?: 0L),
            nice = t[16].toIntOrNull() ?: 0,
            core = t[36].toIntOrNull() ?: -1,
        )
    }

    /** Загрузка потоков по разнице с прошлым замером [prev] (tid → тики) за [dtSec] секунд. */
    fun applyCpu(threads: List<Thread>, prev: Map<Int, Long>, dtSec: Double, clkTck: Long) {
        if (dtSec <= 0) return
        for (t in threads) {
            val old = prev[t.tid] ?: continue
            t.cpu = ((t.ticks - old) * 100.0 / (dtSec * clkTck)).toFloat().coerceAtLeast(0f)
        }
    }

    /** Список дескрипторов и таблицы сокетов; блоки разделены строкой «@». */
    fun filesCommand(pid: Int) = "ls -l /proc/$pid/fd 2>/dev/null; echo @; " +
        "grep -sH '' /proc/$pid/net/tcp /proc/$pid/net/tcp6 /proc/$pid/net/udp /proc/$pid/net/udp6 /proc/$pid/net/unix"

    fun parseFiles(out: String): List<Fd> {
        val lines = out.lines()
        val sep = lines.indexOf("@").let { if (it < 0) lines.size else it }
        val fds = lines.subList(0, sep).mapNotNull { parseFdLine(it) }.sortedBy { it.fd }
        val sockets = parseSockets(lines.subList((sep + 1).coerceAtMost(lines.size), lines.size))
        for (f in fds) if (f.kind == Kind.SOCKET) {
            f.socket = f.target.substringAfter('[', "").substringBefore(']').toLongOrNull()?.let { sockets[it] }
        }
        return fds
    }

    /** Строка `ls -l`: «lrwx------ 1 u g 64 2026-09-28 10:00 12 -> /dev/null». */
    fun parseFdLine(line: String): Fd? {
        val arrow = line.indexOf(" -> ")
        if (arrow < 0) return null
        val fd = line.substring(0, arrow).trimEnd().substringAfterLast(' ').toIntOrNull() ?: return null
        val target = line.substring(arrow + 4)
        val kind = when {
            target.startsWith("socket:") -> Kind.SOCKET
            target.startsWith("pipe:") -> Kind.PIPE
            target.startsWith("/dev/") -> Kind.DEVICE
            target.startsWith("/") -> Kind.FILE
            else -> Kind.OTHER
        }
        return Fd(fd, target, kind)
    }

    private val TCP_STATES = arrayOf("", "ESTABLISHED", "SYN_SENT", "SYN_RECV", "FIN_WAIT1", "FIN_WAIT2",
        "TIME_WAIT", "CLOSE", "CLOSE_WAIT", "LAST_ACK", "LISTEN", "CLOSING")

    /** Строки «/proc/PID/net/tcp:  0: 0100007F:1F90 …» → inode → описание сокета. */
    fun parseSockets(lines: List<String>): Map<Long, String> {
        val map = HashMap<Long, String>()
        for (l in lines) {
            if (':' !in l) continue
            val path = l.substringBefore(':')
            val proto = path.substringAfterLast('/')
            val f = l.substring(path.length + 1).trim().split(Regex("\\s+"))
            if (proto == "unix") {
                val inode = f.getOrNull(6)?.toLongOrNull() ?: continue
                map[inode] = "unix " + (f.getOrNull(7) ?: "socket:[$inode]")
                continue
            }
            val inode = f.getOrNull(9)?.toLongOrNull() ?: continue
            val local = addr(f[1]) ?: continue
            val remote = addr(f[2]) ?: continue
            val name = proto.removeSuffix("6").uppercase()
            val sb = StringBuilder(name).append(' ').append(local)
            val zero = f[2].substringBefore(':').all { it == '0' } && f[2].endsWith(":0000")
            if (!zero) sb.append(" → ").append(remote)
            if (name == "TCP") f[3].toIntOrNull(16)?.let { TCP_STATES.getOrNull(it) }?.let { sb.append(' ').append(it) }
            map[inode] = sb.toString()
        }
        return map
    }

    /** «0100007F:1F90» → «127.0.0.1:8080»; адрес в /proc/net записан 32-битными словами в порядке ядра. */
    fun addr(s: String): String? {
        val hex = s.substringBefore(':')
        val port = s.substringAfter(':', "").toIntOrNull(16) ?: return null
        if (hex.length != 8 && hex.length != 32) return null
        val bytes = ByteArray(hex.length / 2)
        for (w in 0 until hex.length / 8) for (b in 0 until 4) {
            val i = w * 8 + (3 - b) * 2
            bytes[w * 4 + b] = hex.substring(i, i + 2).toIntOrNull(16)?.toByte() ?: return null
        }
        fun v4(from: Int) = (from until from + 4).joinToString(".") { (bytes[it].toInt() and 0xFF).toString() }
        if (bytes.size == 4) return "${v4(0)}:$port"
        // IPv4 внутри IPv6 (::ffff:a.b.c.d) — показываем как IPv4
        if ((0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == (-1).toByte() && bytes[11] == (-1).toByte())
            return "${v4(12)}:$port"
        return "[${v6(bytes)}]:$port"
    }

    /** IPv6 в краткой записи: самая длинная серия нулевых групп (от двух) заменяется на «::». */
    private fun v6(b: ByteArray): String {
        val g = IntArray(8) { ((b[it * 2].toInt() and 0xFF) shl 8) or (b[it * 2 + 1].toInt() and 0xFF) }
        var best = -1; var bestLen = 1
        var i = 0
        while (i < 8) {
            if (g[i] != 0) { i++; continue }
            var j = i
            while (j < 8 && g[j] == 0) j++
            if (j - i > bestLen) { best = i; bestLen = j - i }
            i = j
        }
        fun hex(r: IntRange) = r.joinToString(":") { Integer.toHexString(g[it]) }
        return if (best < 0) hex(0 until 8)
        else hex(0 until best) + "::" + hex(best + bestLen until 8)
    }
}
