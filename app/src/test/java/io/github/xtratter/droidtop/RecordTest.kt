package io.github.xtratter.droidtop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordTest {
    private fun proc(pid: Int, name: String, pkg: String?, cpu: Float, rss: Long, thr: Int) =
        ProcInfo(pid, 1, name, 'S', 0, 20, 0, thr, 0, 0, rss).apply { this.name = name; this.pkg = pkg; this.cpu = cpu }

    private fun snap(procs: List<ProcInfo>, cpu: Float = 12.5f) = Snapshot(
        Access.ROOT, emptyList(), cpu, 8L shl 30, 2L shl 30, 1L shl 30, 4L shl 30, 3L shl 30,
        null, 100.0, procs, 44f, 100, gpu = Gpu(19f, 587, 683, "Adreno 650", 41f),
    )

    @Test
    fun sumsAllProcessesOfThePackage() {
        val t = Record.Target("com.app", "com.app", "App")
        val s = snap(listOf(
            proc(10, "com.app", "com.app", 3f, 100L shl 20, 20),
            proc(11, "com.app:push", "com.app", 1.5f, 50L shl 20, 5),
            proc(12, "other", null, 50f, 1L shl 30, 1),
        ))
        val a = Record.sample(t, s)
        assertEquals(4.5f, a.cpu, 0.001f)
        assertEquals(150L shl 20, a.rss)
        assertEquals(2, a.procs)
        assertEquals(25, a.threads)
        assertEquals("2026-09-28 12:00:00,4.0,4.5,150.0,2,25,12.5,44.0,6144.0,1024.0,19.0,587,,,,,1",
            Record.row("2026-09-28 12:00:00", 4.0, a, s, true))
        assertEquals(Record.HEADER.split(',').size, Record.row("t", 0.0, a, s, true).split(',').size)
    }

    @Test
    fun matchesByNameWhenNotAnApp() {
        val t = Record.Target(null, "surfaceflinger", "surfaceflinger")
        assertTrue(t.matches(proc(1, "surfaceflinger", null, 0f, 0, 1)))
        assertFalse(t.matches(proc(2, "surfaceflinger2", null, 0f, 0, 1)))
    }

    @Test
    fun statsSkipSamplesWhenAppIsNotRunning() {
        val st = Record.Stats()
        st.add(Record.Sample(10f, 100, 1, 1))
        st.add(Record.Sample(0f, 0, 0, 0))
        st.add(Record.Sample(30f, 300, 1, 1))
        assertEquals(3, st.samples)
        assertEquals(20f, st.cpuAvg, 0.001f)
        assertEquals(30f, st.cpuMax, 0.001f)
        assertEquals(200L, st.rssAvg)
    }

    @Test
    fun safeFileName() {
        assertEquals("DroidTop_Т-Банк_2026-09-28_12-00-00.csv", Record.fileName("Т-Банк", "2026-09-28_12-00-00"))
        assertEquals("DroidTop_a_b_x.csv", Record.fileName("a/b: ", "x"))
    }
}
