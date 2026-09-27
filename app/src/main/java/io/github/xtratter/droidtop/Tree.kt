package io.github.xtratter.droidtop

/**
 * Строка списка. В режиме дерева знает глубину, линии-связки и потомков.
 * cont[k] — продолжается ли вниз ветка уровня k (есть ли у предка на глубине k следующий «брат»).
 */
class Row(
    val p: ProcInfo,
    val depth: Int = 0,
    val cont: BooleanArray? = null,
    /** Прямых потомков. */
    val kids: Int = 0,
    /** Всех потомков (для значка «+N» у свёрнутой ветки). */
    val descendants: Int = 0,
    val collapsed: Boolean = false,
)

/** Дерево процессов как F5 в htop: родитель, под ним его потомки; «братья» отсортированы как выбрано. */
object Tree {
    private const val MAX_DEPTH = 60

    fun build(procs: List<ProcInfo>, cmp: Comparator<ProcInfo>, collapsed: Set<Int>): List<Row> {
        val byPid = HashMap<Int, ProcInfo>(procs.size * 2)
        procs.forEach { byPid[it.pid] = it }
        val kids = HashMap<Int, MutableList<ProcInfo>>()
        val roots = ArrayList<ProcInfo>()
        for (p in procs) {
            if (p.ppid != p.pid && byPid.containsKey(p.ppid)) kids.getOrPut(p.ppid) { ArrayList() }.add(p)
            else roots += p
        }
        kids.values.forEach { it.sortWith(cmp) }
        roots.sortWith(cmp)

        val desc = HashMap<Int, Int>()
        fun count(p: ProcInfo, depth: Int): Int = desc.getOrPut(p.pid) {
            if (depth > MAX_DEPTH) 0 else kids[p.pid].orEmpty().sumOf { 1 + count(it, depth + 1) }
        }

        val out = ArrayList<Row>(procs.size)
        val cont = BooleanArray(MAX_DEPTH + 2)
        fun walk(p: ProcInfo, depth: Int) {
            val ch = kids[p.pid].orEmpty()
            val folded = ch.isNotEmpty() && p.pid in collapsed
            out += Row(p, depth, cont.copyOf(depth + 1), ch.size, count(p, depth), folded)
            if (folded || depth >= MAX_DEPTH) return
            ch.forEachIndexed { i, c ->
                cont[depth + 1] = i < ch.size - 1
                walk(c, depth + 1)
            }
        }
        roots.forEach { walk(it, 0) }
        return out
    }

    /** Префикс для таблицы в духе htop: «│ ├─┬ ». */
    fun prefix(r: Row): String {
        val c = r.cont ?: return ""
        if (r.depth == 0) return if (r.kids > 0) (if (r.collapsed) "+ " else "") else ""
        val sb = StringBuilder()
        for (k in 1 until r.depth) sb.append(if (c[k]) "│ " else "  ")
        sb.append(if (c[r.depth]) "├─" else "└─")
        sb.append(if (r.kids > 0) (if (r.collapsed) "+" else "┬") else "─")
        return sb.append(' ').toString()
    }
}
