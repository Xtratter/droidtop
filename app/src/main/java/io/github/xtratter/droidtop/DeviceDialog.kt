package io.github.xtratter.droidtop

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** «Об устройстве»: разделы со сведениями; строку, раздел или всё можно скопировать. */
object DeviceDialog {
    fun show(a: MainActivity) {
        val dp = a.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22f), px(22f), px(22f), px(8f))
        }
        box.addView(TextView(a).apply {
            setText(R.string.dev_title); textSize = 20f; typeface = Ui.medium; setTextColor(Ui.TEXT)
        })
        box.addView(TextView(a).apply {
            setText(R.string.dev_hint); textSize = 12f; setTextColor(Ui.TEXT3); setPadding(0, px(2f), 0, px(4f))
        })
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(a).apply {
            setText(R.string.ch_collecting); textSize = 13f; setTextColor(Ui.TEXT2); setPadding(0, px(12f), 0, px(12f))
        })
        box.addView(body)

        var sections: List<DeviceInfo.Section> = emptyList()
        val d = AlertDialog.Builder(a)
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.copy_all, null)
            .create()

        fun render() {
            body.removeAllViews()
            for (sec in sections) {
                body.addView(TextView(a).apply {
                    text = sec.title; textSize = 14f; typeface = Ui.medium; setTextColor(Ui.primary)
                    setPadding(0, px(16f), 0, px(4f))
                    setOnLongClickListener { Clip.copy(a, sec.title, sec.text()); true }
                })
                for ((k, v) in sec.rows) body.addView(LinearLayout(a).apply {
                    setPadding(0, px(5f), 0, px(5f))
                    foreground = Ui.ripple(a, 8f)
                    addView(TextView(a).apply {
                        text = k; textSize = 13f; setTextColor(Ui.TEXT2); setPadding(0, 0, px(10f), 0)
                    }, LinearLayout.LayoutParams(0, -2, 0.42f))
                    addView(TextView(a).apply {
                        text = v; textSize = 13f; setTextColor(Ui.TEXT)
                    }, LinearLayout.LayoutParams(0, -2, 0.58f))
                    setOnClickListener { Clip.copy(a, k, v) }
                })
            }
        }

        d.show()
        Ui.glassDialog(d)
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (sections.isNotEmpty()) Clip.copy(a, a.getString(R.string.dev_title), text(a, sections))
        }
        val snap = Sampler.last
        Sampler.exec(DeviceInfo.command()) { out ->
            // камеры и датчики опрашиваем не в главном потоке
            Thread {
                val list = DeviceInfo.collect(a.applicationContext, DeviceInfo.parse(out.orEmpty()), snap)
                Handler(Looper.getMainLooper()).post {
                    if (d.isShowing) { sections = list; render() }
                }
            }.start()
        }
    }

    fun text(a: MainActivity, sections: List<DeviceInfo.Section>) =
        "DroidTop · " + a.getString(R.string.dev_title) + "\n\n" + sections.joinToString("\n\n") { it.text() }
}
