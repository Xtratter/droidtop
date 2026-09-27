package io.github.xtratter.droidtop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcParserTest {
    private val fallbackMem = longArrayOf(8L shl 30, 4L shl 30)

    @Test
    fun parsesStatLinesAndCpuDeltas() {
        val p = ProcParser(4096, 100)
        fun out(uptime: String, ticks: Int, idle: Int) = """
            |@S
            |cpu  $ticks 0 0 $idle 0 0 0 0 0 0
            |cpu0 $ticks 0 0 $idle 0 0 0 0 0 0
            |@M
            |MemTotal:        8000000 kB
            |MemFree:         1000000 kB
            |MemAvailable:    3000000 kB
            |SwapTotal:       4000000 kB
            |SwapFree:        3000000 kB
            |@L
            |1.50 1.20 0.90 2/900 12345
            |@U
            |$uptime 100.00
            |@F
            |/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq:1804800
            |@Z
            |/sys/class/thermal/thermal_zone3/type:cpu-0-0-usr
            |/sys/class/thermal/thermal_zone4/type:cpu-0-0-step
            |/sys/class/thermal/thermal_zone9/type:battery
            |@P
            |1 (init) S 0 1 1 0 -1 4194560 1 2 3 4 50 50 0 0 20 0 1 0 5 1000 300 0 0
            |777 (Binder:ab (x) c) R 1 777 0 0 -1 0 0 0 0 0 ${ticks} 0 0 0 10 -10 42 0 900 5000 2500 0
            |""".trimMargin()

        p.parse(out("100.00", 1000, 9000), true, 0.0, fallbackMem, Float.NaN)
        val s = p.parse(out("102.00", 1100, 9100), true, 0.0, fallbackMem, 30f)

        assertEquals(2, s.procs.size)
        val b = s.procs.first { it.pid == 777 }
        assertEquals("Binder:ab (x) c", b.comm)
        assertEquals('R', b.state)
        assertEquals(42, b.threads)
        assertEquals(-10, b.nice)
        assertEquals(2500L * 4096, b.rss)
        assertEquals(50f, b.cpu, 0.01f)        // 100 тиков за 2 с при 100 Гц = половина ядра
        assertEquals(50f, s.cpu, 0.01f)        // 100 занятых из 200
        assertEquals(1804800L, s.cores[0].freqKHz)
        assertEquals(8000000L * 1024, s.memTotal)
        assertEquals(1.5f, s.load!![0], 0.001f)
        assertTrue(p.script().contains("/sys/class/thermal/thermal_zone3/temp"))
        assertTrue(!p.script().contains("thermal_zone4"))
    }

    @Test
    fun appliesNamesFromPs() {
        val p = ProcParser(4096, 100)
        val s = p.parse("@P\n5 (d.process.gms) S 1 5 0 0 -1 0 0 0 0 0 1 1 0 0 20 0 30 0 77 1 1 0\n",
            false, 1.0, fallbackMem, Float.NaN)
        val missing = p.missingNames(s.procs)
        assertEquals(listOf(5), missing)
        p.applyNames("  PID USER     NAME\n    5 u0_a120  com.google.android.gms.persistent\n", s.procs, missing)
        assertEquals("com.google.android.gms.persistent", s.procs[0].name)
        assertEquals("u0_a120", s.procs[0].user)
        assertTrue(p.missingNames(s.procs).isEmpty())
    }

    /** Настоящий скрипт на настоящем /proc (запускается только на Linux). */
    @Test
    fun runsAgainstRealProc() {
        if (!java.io.File("/proc/self/stat").exists()) return
        val p = ProcParser(4096, 100)
        fun sample(): String {
            val pr = ProcessBuilder("sh", "-c", p.script()).redirectErrorStream(false).start()
            return pr.inputStream.bufferedReader().readText().also { pr.waitFor() }
        }
        p.parse(sample(), false, 1.0, fallbackMem, Float.NaN)
        Thread.sleep(300)
        val s = p.parse(sample(), false, 2.0, fallbackMem, Float.NaN)
        println("procs=${s.procs.size} mem=${Fmt.size(s.memTotal)} cores=${s.cores.size} " +
            "freq=${s.cores.map { it.freqKHz }} temp=${s.cpuTemp} load=${s.load?.toList()}")
        println(s.procs.sortedByDescending { it.cpu }.take(5).joinToString("\n") { "${it.pid} ${it.comm} ${it.cpu}% ${Fmt.size(it.rss)}" })
        assertTrue(s.procs.isNotEmpty())
        assertTrue(s.memTotal > 0)
    }
}
