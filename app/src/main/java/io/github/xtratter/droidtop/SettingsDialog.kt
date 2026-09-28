package io.github.xtratter.droidtop

import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Настройки: собираем простые строки-переключатели и строки-выбор. */
object SettingsDialog {
    fun show(a: MainActivity, prefs: Prefs) {
        val dp = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, (8 * dp).toInt())
        }

        fun changed() {
            a.applyPrefs()
            Sampler.refreshNow()
        }

        fun section(title: Int) = box.addView(TextView(a).apply {
            setText(title)
            setTextColor(Ui.primary)
            textSize = 14f
            setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
        })

        fun switch(title: Int, get: () -> Boolean, set: (Boolean) -> Unit) = box.addView(Switch(a).apply {
            setText(title)
            textSize = 16f
            setTextColor(Ui.TEXT)
            minHeight = (48 * dp).toInt()
            isChecked = get()
            setOnCheckedChangeListener { _, v -> set(v); changed() }
        })

        fun choice(title: Int, values: List<Int>, label: (Int) -> String, get: () -> Int, set: (Int) -> Unit) {
            val tv = TextView(a).apply {
                textSize = 16f
                minHeight = (48 * dp).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Ui.TEXT)
            }
            fun refresh() { tv.text = a.getString(title) + ": " + label(get()) }
            refresh()
            tv.setOnClickListener {
                AlertDialog.Builder(a)
                    .setTitle(title)
                    .setSingleChoiceItems(values.map(label).toTypedArray(), values.indexOf(get())) { d, i ->
                        set(values[i]); refresh(); changed(); d.dismiss()
                    }
                    .show().also { Ui.glassDialog(it) }
            }
            box.addView(tv)
        }

        // диалог настроек создаём в конце, а смена темы должна его закрыть
        var self: AlertDialog? = null

        /** Несколько галочек в одном окне: биты [values] в числе [get]. */
        fun flags(title: Int, names: List<Int>, values: List<Int>, get: () -> Int, set: (Int) -> Unit) {
            val tv = TextView(a).apply {
                textSize = 16f
                minHeight = (48 * dp).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Ui.TEXT)
            }
            fun refresh() {
                val on = values.indices.filter { get() and values[it] != 0 }
                tv.text = a.getString(title) + ": " + if (on.isEmpty()) a.getString(R.string.s_nothing)
                else on.joinToString(", ") { a.getString(names[it]).lowercase() }
            }
            refresh()
            tv.setOnClickListener {
                val checked = BooleanArray(values.size) { get() and values[it] != 0 }
                AlertDialog.Builder(a)
                    .setTitle(title)
                    .setMultiChoiceItems(names.map { a.getString(it) }.toTypedArray(), checked) { _, i, on ->
                        set(if (on) get() or values[i] else get() and values[i].inv()); refresh(); changed()
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show().also { Ui.glassDialog(it) }
            }
            box.addView(tv)
        }

        section(R.string.s_appearance)
        val themes = Theme.values().toList()
        choice(R.string.s_theme, themes.indices.toList(), { a.getString(themes[it].title) },
            { themes.indexOf(prefs.theme()) }, { i ->
                if (themes[i] != prefs.theme()) { self?.dismiss(); a.changeTheme(themes[i]) }
            })

        section(R.string.s_list)
        choice(R.string.s_interval, listOf(1000, 2000, 3000, 5000, 10000),
            { a.getString(R.string.seconds, it / 1000) }, { prefs.intervalMs }, { prefs.intervalMs = it })
        choice(R.string.s_font, listOf(8, 10, 11, 12, 13, 14, 16, 18, 20, 24),
            { "$it sp" }, { prefs.fontSp }, { prefs.fontSp = it })
        switch(R.string.view_table, { prefs.tableMode }, { prefs.tableMode = it })
        switch(R.string.s_kernel, { prefs.kernelThreads }, { prefs.kernelThreads = it })
        switch(R.string.s_labels, { prefs.appLabels }, { prefs.appLabels = it })

        section(R.string.overlay)
        val P = OverlayView.Part
        flags(R.string.s_overlay_parts,
            listOf(R.string.p_cpu, R.string.p_temp, R.string.p_cores, R.string.p_ram, R.string.p_gpu, R.string.p_bat),
            listOf(P.CPU, P.TEMP, P.CORES, P.RAM, P.GPU, P.BAT), { prefs.overlayParts }, { prefs.overlayParts = it })
        choice(R.string.s_overlay_top, listOf(0, 1, 3, 5, 8, 10),
            { it.toString() }, { prefs.overlayTop }, { prefs.overlayTop = it })
        choice(R.string.s_overlay_top_by, listOf(0, 1),
            { a.getString(if (it == 1) R.string.by_mem else R.string.by_cpu) },
            { if (prefs.overlayTopMem) 1 else 0 }, { prefs.overlayTopMem = it == 1 })
        choice(R.string.s_overlay_size, listOf(70, 85, 100, 115, 130, 150),
            { "$it %" }, { prefs.overlayScale }, { prefs.overlayScale = it })
        choice(R.string.s_overlay_width, listOf(150, 184, 230),
            { a.getString(when (it) { 150 -> R.string.w_narrow; 230 -> R.string.w_wide; else -> R.string.w_normal }) },
            { prefs.overlayWidth }, { prefs.overlayWidth = it })
        choice(R.string.s_overlay_alpha, listOf(100, 85, 70, 50, 30),
            { "$it %" }, { prefs.overlayAlpha }, { prefs.overlayAlpha = it })
        switch(R.string.s_overlay_lock, { prefs.overlayLock }, { prefs.overlayLock = it })
        switch(R.string.s_overlay_through, { prefs.overlayClickThrough }, { prefs.overlayClickThrough = it })

        self = AlertDialog.Builder(a)
            .setTitle(R.string.settings)
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton(android.R.string.ok, null)
            .show().also { Ui.glassDialog(it) }
    }
}
