package io.github.xtratter.droidtop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceInfoTest {
    private val out = listOf(
        "[ro.board.platform]: [kona]",
        "[ro.product.marketname]: [POCO F3]",
        "[ro.boot.slot_suffix]: []",
        "@",
        "Linux version 4.19.404-InfiniR (gcc) #1 SMP PREEMPT",
        "@",
        "processor\t: 0", "CPU implementer\t: 0x41", "CPU part\t: 0xd05", "",
        "processor\t: 4", "CPU implementer\t: 0x41", "CPU part\t: 0xd0d", "",
        "processor\t: 7", "CPU implementer\t: 0x41", "CPU part\t: 0xd0d",
        "Hardware\t: Qualcomm Technologies, Inc KONA",
        "@",
        "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq:1804800",
        "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq:300000",
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor:schedutil",
        "/sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_max_freq:2419200",
        "/sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_min_freq:710400",
        "/sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq:3187200",
        "/sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_min_freq:844800",
        "@",
        "Enforcing",
        "@",
        "/sys/class/power_supply/battery/cycle_count:312",
        "@",
        "/sys/class/thermal/thermal_zone0/type:cpu-0-0-usr",
        "/sys/class/thermal/thermal_zone1/type:battery",
        "/sys/class/thermal/thermal_zone2/type:broken",
        "/sys/class/thermal/thermal_zone0/temp:44500",
        "/sys/class/thermal/thermal_zone1/temp:35",
        "/sys/class/thermal/thermal_zone2/temp:-273000",
        "@",
        "sda sdb zram0 loop0",
        "@",
        "lzo [lz4] zstd",
        "4294967296",
    ).joinToString("\n")

    @Test
    fun parsesAllBlocks() {
        val r = DeviceInfo.parse(out)
        assertEquals("kona", r.props["ro.board.platform"])
        assertEquals("POCO F3", r.props["ro.product.marketname"])
        assertNull(r.props["ro.boot.slot_suffix"])             // пустое значение не берём
        assertEquals("Enforcing", r.selinux)
        assertEquals("312", r.power["/sys/class/power_supply/battery/cycle_count"])
        assertEquals(listOf("cpu-0-0-usr" to 44.5f, "battery" to 35f), r.thermal)
        assertEquals(listOf("sda", "sdb", "zram0", "loop0"), r.blocks)
        assertEquals("lzo [lz4] zstd", r.zram.first())
    }

    @Test
    fun groupsCoresIntoClusters() {
        val c = DeviceInfo.clusters(DeviceInfo.parse(out))
        assertEquals(3, c.size)
        assertEquals(listOf(0), c[0].cores)
        assertEquals("Cortex-A55", c[0].name)
        assertEquals(300000L, c[0].minKHz)
        assertEquals("Cortex-A77", c[1].name)
        assertEquals(3187200L, c[2].maxKHz)
    }

    @Test
    fun namesArmCores() {
        assertEquals("Cortex-X1", DeviceInfo.coreName(0x41, 0xd44))
        assertEquals("Kryo Silver", DeviceInfo.coreName(0x51, 0x805))
        assertNull(DeviceInfo.coreName(0x41, 0x123))
    }

    @Test
    fun shortensKernelVersion() {
        val (rel, build) = DeviceInfo.kernel("Linux version 4.19.404-InfiniR_Alioth_KSUN_raystef66 (build-user@build-host) " +
            "(Android (14054515, +pgo, based on r563880c) clang version 21.0.0, LLD 21.0.0) #4 SMP PREEMPT Wed Feb 4 19:50:32 CET 2026")
        assertEquals("4.19.404-InfiniR_Alioth_KSUN_raystef66", rel)
        assertEquals("clang 21.0.0 · Wed Feb 4 19:50:32 CET 2026", build)
        assertEquals(null to null, DeviceInfo.kernel(""))
    }

    @Test
    fun mergesWakeupSensors() {
        val merged = DeviceInfo.sensors(listOf(
            "pedometer  Wakeup" to "qualcomm", "pedometer  Non-wakeup" to "qualcomm",
            "pedometer  Wakeup" to "qualcomm", "pedometer  Non-wakeup" to "qualcomm",
            "stationary_detect_wakeup" to "qualcomm", "stationary_detect" to "qualcomm",
            "tcs3701 Ambient Light Sensor Wakeup" to "ams AG", "tcs3701 Ambient Light Sensor Non-wakeup" to "ams AG",
            "Touch Sensor" to "xiaomi", "orientation  Non-wakeup" to "xiaomi"))
        assertEquals(listOf(
            "pedometer" to "qualcomm × 4",
            "stationary_detect" to "qualcomm × 2",
            "tcs3701 Ambient Light Sensor" to "ams AG × 2",
            "Touch Sensor" to "xiaomi",
            "orientation" to "xiaomi"), merged)
    }
}
