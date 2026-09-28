package io.github.xtratter.droidtop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTest {
    @Test
    fun parsesAdreno() {
        val g = Gpu.parse(listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage:37 %",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq:587000000",
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq:670000000",
            "/sys/class/kgsl/kgsl-3d0/gpu_model:Adreno650v2",
        ), 41f)!!
        assertEquals(37f, g.busy, 0.01f)
        assertEquals(587, g.freqMHz)
        assertEquals(670, g.maxMHz)
        assertEquals("Adreno 650", g.model)
        assertEquals(41f, g.temp, 0.01f)
        assertEquals(0.37f, g.fraction, 0.001f)
    }

    @Test
    fun fallsBackToGpubusyCounters() {
        val g = Gpu.parse(listOf("/sys/class/kgsl/kgsl-3d0/gpubusy:  25000  100000"), Float.NaN)!!
        assertEquals(25f, g.busy, 0.01f)
    }

    @Test
    fun parsesMaliInMegahertz() {
        val g = Gpu.parse(listOf(
            "/sys/kernel/gpu/gpu_busy:12",
            "/sys/kernel/gpu/gpu_clock:848",
            "/sys/kernel/gpu/gpu_max_clock:848",
            "/sys/kernel/gpu/gpu_model:Mali-G78",
        ), Float.NaN)!!
        assertEquals(12f, g.busy, 0.01f)
        assertEquals(848, g.freqMHz)
        assertEquals("Mali-G78", g.model)
    }

    @Test
    fun frequencyOnlyUsesRatio() {
        val g = Gpu.parse(listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq:335000000",
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq:670000000",
        ), Float.NaN)!!
        assertTrue(g.busy.isNaN())
        assertEquals(0.5f, g.fraction, 0.001f)
    }

    @Test
    fun nothingReadableGivesNull() {
        assertNull(Gpu.parse(emptyList(), Float.NaN))
    }

    @Test
    fun parserPicksGpuThermalZones() {
        val parser = ProcParser(4096, 100)
        val out = parser.script()
        assertTrue("gpu_busy_percentage" in out && "thermal_zone*/type" in out)
        val s = parser.parse("""
/sys/class/thermal/thermal_zone1/type:cpu-0-0-usr
/sys/class/thermal/thermal_zone20/type:gpuss-0-usr
/sys/class/thermal/thermal_zone21/type:gpuss-max-step
/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage:5 %
""".trimIndent(), Access.ROOT, 100.0, longArrayOf(0, 0))
        assertEquals(5f, s.gpu!!.busy, 0.01f)
        val next = parser.script()
        assertTrue("thermal_zone20/temp" in next && "thermal_zone21" !in next)
        assertTrue("gpu_busy_percentage" in next && "gpubusy" !in next)
        val s2 = parser.parse("""
/sys/class/thermal/thermal_zone1/temp:52000
/sys/class/thermal/thermal_zone20/temp:44000
/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage:9 %
""".trimIndent(), Access.ROOT, 101.0, longArrayOf(0, 0))
        assertEquals(52f, s2.cpuTemp, 0.01f)
        assertEquals(44f, s2.gpu!!.temp, 0.01f)
    }
}
