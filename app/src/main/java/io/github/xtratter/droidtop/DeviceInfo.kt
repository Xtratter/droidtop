package io.github.xtratter.droidtop

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Сведения об устройстве в духе AIDA64: разделы «ключ — значение». */
object DeviceInfo {
    class Section(val title: String) {
        val rows = ArrayList<Pair<String, String>>()
        fun add(key: String, value: String?) { if (!value.isNullOrBlank()) rows += key to value.trim() }
        fun text() = "== $title ==\n" + rows.joinToString("\n") { "${it.first}: ${it.second}" }
    }

    /** Что удалось прочитать через оболочку (с root — больше). */
    class Raw(
        val props: Map<String, String>,
        val kernel: String,
        val cpuinfo: List<String>,
        val cpufreq: Map<String, String>,
        val selinux: String,
        val power: Map<String, String>,
        val thermal: List<Pair<String, Float>>,
        val blocks: List<String>,
        val zram: List<String>,
    )

    private val PROPS = listOf(
        "ro.product.marketname", "ro.product.vendor.marketname", "ro.board.platform", "ro.soc.model",
        "ro.soc.manufacturer", "ro.hardware", "ro.treble.enabled", "ro.boot.slot_suffix",
        "ro.product.first_api_level", "ro.boot.verifiedbootstate", "ro.boot.flash.locked", "ro.hardware.egl",
        "ro.build.version.incremental", "ro.miui.ui.version.name", "ro.build.version.oneui",
        "ro.lineage.version", "ro.modversion", "ro.crypto.type",
    )

    /** Одна команда на всё; блоки разделены строкой «@». */
    fun command(): String {
        val cpu = "/sys/devices/system/cpu/cpu*/cpufreq"
        val ps = "/sys/class/power_supply"
        return "getprop | grep -F -e '" + PROPS.joinToString("]' -e '") { "[$it" } + "]'; echo @; " +
            "cat /proc/version 2>/dev/null; echo @; cat /proc/cpuinfo 2>/dev/null; echo @; " +
            "grep -sH '' $cpu/cpuinfo_max_freq $cpu/cpuinfo_min_freq $cpu/scaling_governor; echo @; " +
            "getenforce 2>/dev/null; echo @; " +
            "grep -sH '' $ps/battery/charge_full $ps/battery/charge_full_design $ps/battery/cycle_count " +
            "$ps/bms/charge_full $ps/bms/charge_full_design; echo @; " +
            "grep -sH '' /sys/class/thermal/thermal_zone*/type /sys/class/thermal/thermal_zone*/temp; echo @; " +
            "ls /sys/block 2>/dev/null; echo @; " +
            "cat /sys/block/zram0/comp_algorithm /sys/block/zram0/disksize 2>/dev/null"
    }

    fun parse(out: String): Raw {
        val b = out.split("\n@\n")
        fun block(i: Int) = b.getOrNull(i)?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val props = HashMap<String, String>()
        for (l in block(0)) {
            // [ro.soc.model]: [SM8250]
            val k = l.substringAfter('[').substringBefore(']')
            val v = l.substringAfter("]: [", "").substringBeforeLast(']')
            if (k.isNotEmpty() && v.isNotEmpty()) props[k] = v
        }
        fun pathMap(lines: List<String>) = lines.associate { it.substringBefore(':') to it.substringAfter(':').trim() }
        // пары type/temp одной зоны
        val zones = pathMap(block(6))
        val thermal = zones.keys.filter { it.endsWith("/type") }.mapNotNull { k ->
            val dir = k.removeSuffix("/type")
            val t = zones["$dir/temp"]?.toFloatOrNull() ?: return@mapNotNull null
            val c = if (t > 1000f || t < -1000f) t / 1000f else t
            if (c <= 0f || c > 150f) null else zones.getValue(k) to c
        }.sortedByDescending { it.second }
        return Raw(
            props = props,
            kernel = block(1).joinToString(" "),
            cpuinfo = block(2),
            cpufreq = pathMap(block(3)),
            selinux = block(4).firstOrNull().orEmpty(),
            power = pathMap(block(5)),
            thermal = thermal,
            blocks = block(7).flatMap { it.split(Regex("\\s+")) }.filter { it.isNotEmpty() },
            zram = block(8),
        )
    }

    class Cluster(val cores: List<Int>, val name: String?, val minKHz: Long, val maxKHz: Long)

    /** Ядра с одинаковой максимальной частотой — один кластер; имена ядер из /proc/cpuinfo. */
    fun clusters(raw: Raw): List<Cluster> {
        fun freq(cpu: Int, f: String) =
            raw.cpufreq["/sys/devices/system/cpu/cpu$cpu/cpufreq/$f"]?.toLongOrNull() ?: 0L
        val parts = coreNames(raw.cpuinfo)
        val cpus = raw.cpufreq.keys.mapNotNull { Regex("/cpu(\\d+)/").find(it)?.groupValues?.get(1)?.toInt() }
            .distinct().sorted()
        return cpus.groupBy { freq(it, "cpuinfo_max_freq") }.map { (max, list) ->
            Cluster(list, list.mapNotNull { parts[it] }.distinct().joinToString(" / ").ifEmpty { null },
                list.minOf { freq(it, "cpuinfo_min_freq") }, max)
        }.sortedBy { it.maxKHz }
    }

    /** Номер ядра → название по CPU implementer / part. */
    fun coreNames(cpuinfo: List<String>): Map<Int, String> {
        val res = HashMap<Int, String>()
        var cpu = -1; var impl = -1
        for (l in cpuinfo) {
            val k = l.substringBefore(':').trim()
            val v = l.substringAfter(':', "").trim()
            when (k) {
                "processor" -> cpu = v.toIntOrNull() ?: -1
                "CPU implementer" -> impl = v.removePrefix("0x").toIntOrNull(16) ?: -1
                "CPU part" -> v.removePrefix("0x").toIntOrNull(16)?.let { p -> coreName(impl, p)?.let { if (cpu >= 0) res[cpu] = it } }
            }
        }
        return res
    }

    fun coreName(impl: Int, part: Int): String? = when (impl) {
        0x41 -> when (part) {
            0xd03 -> "Cortex-A53"; 0xd04 -> "Cortex-A35"; 0xd05 -> "Cortex-A55"; 0xd07 -> "Cortex-A57"
            0xd08 -> "Cortex-A72"; 0xd09 -> "Cortex-A73"; 0xd0a -> "Cortex-A75"; 0xd0b -> "Cortex-A76"
            0xd0d -> "Cortex-A77"; 0xd41 -> "Cortex-A78"; 0xd44 -> "Cortex-X1"; 0xd46 -> "Cortex-A510"
            0xd47 -> "Cortex-A710"; 0xd48 -> "Cortex-X2"; 0xd4d -> "Cortex-A715"; 0xd4e -> "Cortex-X3"
            0xd80 -> "Cortex-A520"; 0xd81 -> "Cortex-A720"; 0xd82 -> "Cortex-X4"; 0xd85 -> "Cortex-X925"
            0xd87 -> "Cortex-A725"
            else -> null
        }
        0x51 -> when (part) {
            0x800, 0x802, 0x804 -> "Kryo Gold"; 0x801, 0x803, 0x805 -> "Kryo Silver"
            else -> null
        }
        else -> null
    }

    /** Собрать все разделы (вызывать из фонового потока: камеры и датчики опрашиваются не мгновенно). */
    fun collect(ctx: Context, raw: Raw, snap: Snapshot?): List<Section> {
        fun s(id: Int) = ctx.getString(id)
        fun size(b: Long) = Human.size(ctx, b)
        val p = raw.props
        val out = ArrayList<Section>()

        out += Section(s(R.string.dev_s_device)).apply {
            add(s(R.string.dev_model), p["ro.product.marketname"] ?: p["ro.product.vendor.marketname"])
            add(s(R.string.dev_model_code), "${Build.MANUFACTURER} ${Build.MODEL}")
            add(s(R.string.dev_codename), Build.DEVICE)
            add(s(R.string.dev_brand), Build.BRAND)
            add(s(R.string.dev_board), Build.BOARD)
            add(s(R.string.dev_hardware), Build.HARDWARE)
        }

        out += Section(s(R.string.dev_s_system)).apply {
            add("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            add(s(R.string.dev_patch), Build.VERSION.SECURITY_PATCH)
            val ui = p["ro.miui.ui.version.name"]?.let { "MIUI/HyperOS $it" } ?: p["ro.build.version.oneui"]?.let { "One UI $it" }
                ?: p["ro.lineage.version"]?.let { "LineageOS $it" } ?: p["ro.modversion"]
            add(s(R.string.dev_rom), ui)
            add(s(R.string.dev_build), Build.DISPLAY)
            add(s(R.string.dev_incremental), p["ro.build.version.incremental"])
            add(s(R.string.dev_fingerprint), Build.FINGERPRINT)
            val k = kernel(raw.kernel)
            add(s(R.string.dev_kernel), k.first ?: System.getProperty("os.version"))
            add(s(R.string.dev_kernel_build), k.second)
            add("SELinux", raw.selinux)
            add(s(R.string.dev_first_api), p["ro.product.first_api_level"])
            add("Treble", p["ro.treble.enabled"]?.let { if (it == "true") s(R.string.yes) else s(R.string.no) })
            add(s(R.string.dev_slot), p["ro.boot.slot_suffix"]?.removePrefix("_")?.uppercase())
            add(s(R.string.dev_bootloader), Build.BOOTLOADER)
            add(s(R.string.dev_locked), p["ro.boot.flash.locked"]?.let { if (it == "1") s(R.string.yes) else s(R.string.no) })
            add("Verified Boot", p["ro.boot.verifiedbootstate"])
            add(s(R.string.dev_baseband), Build.getRadioVersion())
            add(s(R.string.dev_encryption), p["ro.crypto.type"])
            add("ART", System.getProperty("java.vm.version"))
            snap?.let { add(s(R.string.dev_uptime), uptime(ctx, it.uptime.toLong())) }
        }

        out += Section(s(R.string.dev_s_cpu)).apply {
            val soc = if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != Build.UNKNOWN)
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else p["ro.soc.model"]
            add(s(R.string.dev_soc), soc)
            add(s(R.string.dev_platform), p["ro.board.platform"])
            raw.cpuinfo.firstOrNull { it.startsWith("Hardware") }?.let { add(s(R.string.dev_hardware), it.substringAfter(':')) }
            add(s(R.string.dev_cores), Runtime.getRuntime().availableProcessors().toString())
            clusters(raw).forEachIndexed { i, c ->
                val range = if (c.minKHz > 0) Human.ghz(ctx, c.minKHz) + " – " + Human.ghz(ctx, c.maxKHz) else Human.ghz(ctx, c.maxKHz)
                add(ctx.getString(R.string.dev_cluster, i + 1),
                    "${c.cores.size} × ${c.name ?: "CPU"} · $range (${c.cores.joinToString(",")})")
            }
            add(s(R.string.dev_governor), raw.cpufreq["/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"])
            add("ABI", Build.SUPPORTED_ABIS.joinToString(", "))
            raw.cpuinfo.firstOrNull { it.startsWith("Features") }?.let { add(s(R.string.dev_features), it.substringAfter(':')) }
        }

        out += Section(s(R.string.dev_s_memory)).apply {
            snap?.let {
                add(s(R.string.dev_ram), size(it.memTotal))
                add(s(R.string.dev_ram_avail), size(it.memAvail))
                if (it.swapTotal > 0) add(s(R.string.dev_swap), size(it.swapTotal) + " · " +
                    ctx.getString(R.string.dev_swap_free, size(it.swapFree)))
            }
            raw.zram.firstOrNull()?.let { alg -> Regex("\\[(\\w+)]").find(alg)?.groupValues?.get(1)?.let { add(s(R.string.dev_zram), it) } }
            try {
                val st = StatFs(Environment.getDataDirectory().path)
                add(s(R.string.dev_storage), ctx.getString(R.string.dev_storage_v, size(st.totalBytes), size(st.availableBytes)))
            } catch (_: Exception) {}
            val type = when {
                raw.blocks.any { it.startsWith("sd") } -> "UFS"
                raw.blocks.any { it.startsWith("mmcblk") } -> "eMMC"
                raw.blocks.any { it.startsWith("nvme") } -> "NVMe"
                else -> null
            }
            add(s(R.string.dev_storage_type), type)
        }

        out += Section(s(R.string.dev_s_display)).apply {
            try {
                val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                val m = d.mode
                val dm = ctx.resources.displayMetrics
                add(s(R.string.dev_resolution), "${m.physicalWidth} × ${m.physicalHeight}")
                val inch = hypot(m.physicalWidth / dm.xdpi.toDouble(), m.physicalHeight / dm.ydpi.toDouble())
                add(s(R.string.dev_diagonal), String.format(Locale.getDefault(), "%.2f″", inch))
                add(s(R.string.dev_density), "${dm.densityDpi} dpi")
                add(s(R.string.dev_refresh), d.supportedModes.map { it.refreshRate.roundToInt() }.distinct().sorted()
                    .joinToString(", ") { "$it" } + " Hz")
                @Suppress("DEPRECATION")
                val hdr = d.hdrCapabilities?.supportedHdrTypes?.toList()?.mapNotNull {
                    when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> null }
                }.orEmpty()
                add("HDR", hdr.joinToString(", ").ifEmpty { s(R.string.no) })
                add(s(R.string.dev_wide_color), if (d.isWideColorGamut) s(R.string.yes) else s(R.string.no))
            } catch (_: Exception) {}
        }

        out += Section(s(R.string.dev_s_gpu)).apply {
            val g = snap?.gpu
            add(s(R.string.dev_model), g?.model)
            if (g != null && g.maxMHz > 0) add(s(R.string.dev_max_freq), "${g.maxMHz} " + s(R.string.u_mhz))
            add("OpenGL ES", ctx.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.glEsVersion)
            ctx.packageManager.systemAvailableFeatures.firstOrNull { it.name == "android.hardware.vulkan.version" }?.let {
                add("Vulkan", "${it.version shr 22}.${(it.version shr 12) and 0x3FF}")
            }
            add(s(R.string.dev_driver), p["ro.hardware.egl"])
        }

        out += Section(s(R.string.dev_s_battery)).apply {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                add(s(R.string.dev_level), "${i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale}%")
                add(s(R.string.dev_health), when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> s(R.string.dev_h_good)
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> s(R.string.dev_h_overheat)
                    BatteryManager.BATTERY_HEALTH_DEAD -> s(R.string.dev_h_dead)
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> s(R.string.dev_h_voltage)
                    BatteryManager.BATTERY_HEALTH_COLD -> s(R.string.dev_h_cold)
                    else -> null
                })
                add(s(R.string.dev_tech), i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
                add(s(R.string.dev_voltage), String.format(Locale.getDefault(), "%.3f V", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f))
                add(s(R.string.dev_temp), Fmt.temp(i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f))
                val cycles = i.getIntExtra("android.os.extra.CYCLE_COUNT", -1).takeIf { it >= 0 }
                    ?: raw.power["/sys/class/power_supply/battery/cycle_count"]?.toIntOrNull()
                add(s(R.string.dev_cycles), cycles?.toString())
            }
            fun mah(k: String) = (raw.power["/sys/class/power_supply/battery/$k"] ?: raw.power["/sys/class/power_supply/bms/$k"])
                ?.toLongOrNull()?.takeIf { it > 0 }?.let { it / 1000 }
            val design = mah("charge_full_design") ?: powerProfileCapacity(ctx)
            val full = mah("charge_full")
            add(s(R.string.dev_capacity_design), design?.let { "$it mAh" })
            if (full != null) add(s(R.string.dev_capacity_now), "$full mAh" +
                (design?.takeIf { it > 0 }?.let { " (${full * 100 / it}%)" } ?: ""))
        }

        out += Section(s(R.string.dev_s_cameras)).apply {
            try {
                val cm = ctx.getSystemService(CameraManager::class.java)
                for (id in cm.cameraIdList) {
                    val c = cm.getCameraCharacteristics(id)
                    val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_BACK -> s(R.string.dev_cam_back)
                        CameraCharacteristics.LENS_FACING_FRONT -> s(R.string.dev_cam_front)
                        else -> s(R.string.dev_cam_ext)
                    }
                    val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val mp = px?.let { String.format(Locale.getDefault(), "%.1f MP", it.width * it.height / 1e6) }
                    val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.let { "f/$it" }
                    val fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.let {
                        String.format(Locale.getDefault(), "%.1f mm", it)
                    }
                    add(ctx.getString(R.string.dev_camera, id, facing), listOfNotNull(mp, f, fl).joinToString(" · "))
                }
            } catch (_: Exception) {}
        }

        out += Section(s(R.string.dev_s_sensors)).apply {
            val list = ctx.getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL)
            for ((name, vendor) in sensors(list.map { it.name to it.vendor })) add(name, vendor)
        }

        if (raw.thermal.isNotEmpty()) out += Section(s(R.string.dev_s_thermal)).apply {
            for ((name, t) in raw.thermal) add(name, String.format(Locale.getDefault(), "%.1f°C", t))
        }
        return out.filter { it.rows.isNotEmpty() }
    }

    /**
     * Android отдаёт многие датчики дважды — «pedometer Wakeup» и «pedometer Non-wakeup», а то и по
     * нескольку штук: склеиваем их по названию без этого хвоста и производителю, добавляя «× N».
     */
    fun sensors(list: List<Pair<String, String>>): List<Pair<String, String>> {
        val suffix = Regex("[\\s_-]*(non[\\s_-]?wake[\\s_-]?up|wake[\\s_-]?up)$", RegexOption.IGNORE_CASE)
        val groups = LinkedHashMap<Pair<String, String>, Int>()
        for ((name, vendor) in list) {
            val base = name.trim().replace(suffix, "").ifEmpty { name.trim() }
            val key = base to vendor.trim()
            groups[key] = (groups[key] ?: 0) + 1
        }
        return groups.map { (k, n) -> k.first to if (n > 1) "${k.second} × $n" else k.second }
    }

    /** «Linux version X (…) (… clang version 21.0.0 …) #4 SMP PREEMPT <дата>» → X и «clang 21.0.0 · дата». */
    fun kernel(v: String): Pair<String?, String?> {
        if (!v.startsWith("Linux version ")) return null to null
        val release = v.removePrefix("Linux version ").substringBefore(' ')
        val cc = Regex("(clang|gcc) version ([\\w.]+)").find(v)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
        val date = Regex("#\\d+\\s+(?:SMP\\s+)?(?:PREEMPT\\S*\\s+)?(.+)$").find(v)?.groupValues?.get(1)
        return release to listOfNotNull(cc, date).joinToString(" · ").ifEmpty { null }
    }

    /** Паспортная ёмкость из системного PowerProfile (скрытый класс, работает без root). */
    private fun powerProfileCapacity(ctx: Context): Long? = try {
        val c = Class.forName("com.android.internal.os.PowerProfile")
        val pp = c.getConstructor(Context::class.java).newInstance(ctx)
        (c.getMethod("getBatteryCapacity").invoke(pp) as Double).toLong().takeIf { it > 0 }
    } catch (_: Throwable) { null }

    private fun uptime(ctx: Context, sec: Long): String =
        ctx.getString(R.string.dur_dhm, sec / 86400, sec / 3600 % 24, sec / 60 % 60)
}
