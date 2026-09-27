package io.github.xtratter.droidtop

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Сохранённые настройки. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var root by bool("root", false)
    var intervalMs by int("interval_ms", 2000)
    var kernelThreads by bool("kernel_threads", false)
    var appLabels by bool("app_labels", true)
    var fontSp by int("font_sp", 12)
    var sort by str("sort", Sort.CPU.name)
    var sortAsc by bool("sort_asc", false)

    var tableMode by bool("table_mode", false)
    var treeMode by bool("tree_mode", false)

    var overlayTop by int("overlay_top", 3)
    var overlayAlpha by int("overlay_alpha", 85)
    var overlayClickThrough by bool("overlay_click_through", false)
    var overlayX by int("overlay_x", 0)
    var overlayY by int("overlay_y", 160)

    fun sortKey(): Sort = runCatching { Sort.valueOf(sort) }.getOrDefault(Sort.CPU)

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            sp.edit().putBoolean(key, value).apply()
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) =
            sp.edit().putInt(key, value).apply()
    }

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) =
            sp.edit().putString(key, value).apply()
    }
}
