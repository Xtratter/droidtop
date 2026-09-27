package io.github.xtratter.droidtop

import org.junit.Assert.assertEquals
import org.junit.Test

class TreeTest {
    private fun p(pid: Int, ppid: Int, cpu: Float = 0f) =
        ProcInfo(pid, ppid, "p$pid", 'S', 0, 20, 0, 1, 0, 0, 0).apply { this.cpu = cpu; title = "p$pid" }

    // init(1) ─┬ zygote(10) ─┬ app(20, 5%) ── child(30)
    //          │             └ app(21, 9%)
    //          └ daemon(11)
    private val procs = listOf(p(1, 0), p(10, 1, 1f), p(11, 1), p(20, 10, 5f), p(21, 10, 9f), p(30, 20))

    @Test
    fun buildsDepthFirstWithSiblingsSorted() {
        val rows = Tree.build(procs, Sort.CPU.comparator(false), emptySet())
        assertEquals(listOf(1, 10, 21, 20, 30, 11), rows.map { it.p.pid })
        assertEquals(listOf(0, 1, 2, 2, 3, 1), rows.map { it.depth })
        assertEquals(5, rows[0].descendants)
        assertEquals("├─┬ ", Tree.prefix(rows[1]))    // zygote: не последний, есть дети
        assertEquals("│ └─┬ ", Tree.prefix(rows[3]))  // app 20: последний у zygote, есть ребёнок
        assertEquals("│   └── ", Tree.prefix(rows[4]))
        assertEquals("└── ", Tree.prefix(rows[5]))
    }

    @Test
    fun collapsedBranchHidesDescendants() {
        val rows = Tree.build(procs, Sort.PID.comparator(true), setOf(10))
        assertEquals(listOf(1, 10, 11), rows.map { it.p.pid })
        assertEquals(true, rows[1].collapsed)
        assertEquals(3, rows[1].descendants)
        assertEquals("├─+ ", Tree.prefix(rows[1]))
    }
}
