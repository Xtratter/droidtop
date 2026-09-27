package io.github.xtratter.droidtop

import android.app.AlertDialog
import android.graphics.Color
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
            setTextColor(Palette.GREEN)
            textSize = 14f
            setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
        })

        fun switch(title: Int, get: () -> Boolean, set: (Boolean) -> Unit) = box.addView(Switch(a).apply {
            setText(title)
            textSize = 16f
            minHeight = (48 * dp).toInt()
            isChecked = get()
            setOnCheckedChangeListener { _, v -> set(v); changed() }
        })

        fun choice(title: Int, values: List<Int>, label: (Int) -> String, get: () -> Int, set: (Int) -> Unit) {
            val tv = TextView(a).apply {
                textSize = 16f
                minHeight = (48 * dp).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
            }
            fun refresh() { tv.text = a.getString(title) + ": " + label(get()) }
            refresh()
            tv.setOnClickListener {
                AlertDialog.Builder(a)
                    .setTitle(title)
                    .setSingleChoiceItems(values.map(label).toTypedArray(), values.indexOf(get())) { d, i ->
                        set(values[i]); refresh(); changed(); d.dismiss()
                    }
                    .show()
            }
            box.addView(tv)
        }

        section(R.string.s_list)
        choice(R.string.s_interval, listOf(1000, 2000, 3000, 5000, 10000),
            { a.getString(R.string.seconds, it / 1000) }, { prefs.intervalMs }, { prefs.intervalMs = it })
        choice(R.string.s_font, listOf(10, 11, 12, 13, 14, 16),
            { "$it sp" }, { prefs.fontSp }, { prefs.fontSp = it })
        switch(R.string.s_kernel, { prefs.kernelThreads }, { prefs.kernelThreads = it })
        switch(R.string.s_labels, { prefs.appLabels }, { prefs.appLabels = it })

        section(R.string.overlay)
        choice(R.string.s_overlay_top, listOf(0, 1, 3, 5),
            { it.toString() }, { prefs.overlayTop }, { prefs.overlayTop = it })
        choice(R.string.s_overlay_alpha, listOf(100, 85, 70, 50, 30),
            { "$it %" }, { prefs.overlayAlpha }, { prefs.overlayAlpha = it })
        switch(R.string.s_overlay_through, { prefs.overlayClickThrough }, { prefs.overlayClickThrough = it })

        AlertDialog.Builder(a)
            .setTitle(R.string.settings)
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
