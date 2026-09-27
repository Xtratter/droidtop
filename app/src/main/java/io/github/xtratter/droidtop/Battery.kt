package io.github.xtratter.droidtop

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs

/** Состояние батареи: ток, напряжение, мощность и прогноз. Работает без root. */
class Battery(
    val level: Int,
    val charging: Boolean,
    val plugged: Boolean,
    /** Ток по модулю, мА (0 — телефон не сообщает). */
    val currentMa: Float,
    val voltage: Float,
    /** Мощность по модулю, Вт. */
    val powerW: Float,
    val temp: Float,
    /** Сколько минут до разряда (или до полного заряда при зарядке), -1 — неизвестно. */
    val minutesLeft: Int,
) {
    companion object {
        fun read(ctx: Context): Battery? {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            var mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).toFloat()
            if (mv in 1f..100f) mv *= 1000f                     // некоторые отдают вольты
            val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)

            // ток обычно в микроамперах, но часть прошивок отдаёт миллиамперы; знак у разных производителей разный
            var ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()
            if (ua == Long.MIN_VALUE || ua == Int.MIN_VALUE.toLong()) ua = 0
            if (ua != 0L && abs(ua) < 10_000) ua *= 1000
            val ma = abs(ua) / 1000f
            val watts = mv / 1000f * ma / 1000f

            var minutes = -1
            if (charging) {
                if (Build.VERSION.SDK_INT >= 28) {
                    val ms = bm.computeChargeTimeRemaining()
                    if (ms > 0) minutes = (ms / 60000).toInt()
                }
            } else if (ma > 20f) {
                val uah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                if (uah > 0 && uah != Int.MIN_VALUE) minutes = (uah / 1000f / ma * 60).toInt()
            }
            return Battery(level, charging, plugged, ma, mv / 1000f, watts,
                if (t == Int.MIN_VALUE) Float.NaN else t / 10f, minutes)
        }
    }
}
