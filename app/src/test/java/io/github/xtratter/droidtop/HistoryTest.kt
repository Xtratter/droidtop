package io.github.xtratter.droidtop

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {
    private fun snap(uptime: Double) = Snapshot(
        access = Access.ROOT, cores = emptyList(), cpu = 10f, memTotal = 100, memAvail = 50, memFree = 50,
        swapTotal = 0, swapFree = 0, load = null, uptime = uptime, procs = emptyList(), cpuTemp = Float.NaN, clkTck = 100,
    )

    @Test
    fun pauseDoesNotStretchTimeScale() {
        for (t in 0..10) History.add(snap(1000.0 + t * 2))
        assertEquals(2f, History.secondsPerPoint, 0.05f)
        History.add(snap(1400.0))          // экран был выключен почти 7 минут
        History.add(snap(1400.3))          // быстрый замер после настройки
        for (t in 1..3) History.add(snap(1400.3 + t * 2))
        assertEquals(2f, History.secondsPerPoint, 0.05f)
    }
}
