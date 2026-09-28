package io.github.xtratter.droidtop

import android.app.AlertDialog
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Списки потоков и открытых файлов процесса — открываются поверх его карточки. */
object InspectDialog {
    private const val MAX_THREADS = 300

    private class Frame(val box: LinearLayout, val title: TextView, val body: LinearLayout)

    /** Заголовок, подзаголовок и место под список. */
    private fun frame(a: MainActivity, p: ProcInfo): Frame {
        val dp = a.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22f), px(22f), px(22f), px(8f))
        }
        val title = TextView(a).apply { textSize = 20f; typeface = Ui.medium; setTextColor(Ui.TEXT) }
        box.addView(title)
        box.addView(TextView(a).apply {
            text = a.getString(R.string.row_sub, p.pid, p.user.ifEmpty { "?" }, p.title)
            textSize = 13f; setTextColor(Ui.TEXT2); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(10f), 0, 0) }
        box.addView(body)
        return Frame(box, title, body)
    }

    private fun note(a: MainActivity, text: String) = TextView(a).apply {
        this.text = text; textSize = 13f; setTextColor(Ui.TEXT2)
        val pad = (6 * a.resources.displayMetrics.density).toInt()
        setPadding(pad, pad * 2, pad, pad * 2)
    }

    private fun dialog(a: MainActivity, f: Frame): AlertDialog =
        AlertDialog.Builder(a)
            .setView(ScrollView(a).apply { addView(f.box) })
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.copy, null)
            .create()

    /** «Копировать» не закрывает диалог; [text] — null, пока список не загружен. */
    private fun copyButton(a: MainActivity, d: AlertDialog, p: ProcInfo, text: () -> String?) =
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            text()?.let { Clip.copy(a, p.title, "${p.title} (PID ${p.pid})\n$it") }
        }

    /** Потоки с загрузкой CPU — обновляются с каждым замером, пока список открыт. */
    fun showThreads(a: MainActivity, p: ProcInfo) {
        val dp = a.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val f = frame(a, p)
        f.title.text = a.getString(R.string.th_title, p.threads)
        f.body.addView(note(a, a.getString(R.string.ch_collecting)))

        class Row(val view: View, val name: TextView, val sub: TextView, val cpu: TextView)
        val rows = ArrayList<Row>()
        fun row(): Row {
            val name = TextView(a).apply {
                textSize = 14f; setTextColor(Ui.TEXT); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val sub = TextView(a).apply { textSize = 12f; setTextColor(Ui.TEXT2); maxLines = 1 }
            val cpu = TextView(a).apply { textSize = 13f; typeface = Ui.bold; gravity = Gravity.END }
            val v = LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(4f), px(6f), px(4f), px(6f))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(name); addView(sub)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(cpu, LinearLayout.LayoutParams(px(64f), -2))
            }
            return Row(v, name, sub, cpu)
        }
        val more = TextView(a).apply { textSize = 12f; setTextColor(Ui.TEXT3); setPadding(px(4f), px(6f), 0, 0) }

        var last: List<Inspect.Thread> = emptyList()
        var prev: Map<Int, Long> = emptyMap()
        var prevAt = 0L
        var busy = false
        var dlg: AlertDialog? = null

        fun show(list: List<Inspect.Thread>) {
            f.title.text = a.getString(R.string.th_title, list.size)
            if (list.isEmpty()) {
                f.body.removeAllViews(); rows.clear()
                f.body.addView(note(a, a.getString(R.string.th_none)))
                return
            }
            if (rows.isEmpty()) f.body.removeAllViews()
            val sorted = list.sortedWith(compareByDescending<Inspect.Thread> { if (it.cpu.isNaN()) -1f else it.cpu }
                .thenByDescending { it.ticks })
            val shown = sorted.take(MAX_THREADS)
            while (rows.size < shown.size) row().also { rows += it; f.body.addView(it.view, rows.size - 1) }
            while (rows.size > shown.size) f.body.removeView(rows.removeAt(rows.size - 1).view)
            shown.forEachIndexed { i, t ->
                val r = rows[i]
                r.name.text = t.name
                val sub = StringBuilder(a.getString(R.string.th_sub, t.tid, a.getString(ProcessDialog.stateName(t.state))))
                if (t.core >= 0) sub.append(" · ").append(a.getString(R.string.th_core, t.core))
                if (t.nice != 0) sub.append(" · nice ").append(t.nice)
                r.sub.text = sub
                r.cpu.text = if (t.cpu.isNaN()) "…" else Fmt.pct(t.cpu) + "%"
                r.cpu.setTextColor(if (t.cpu.isNaN() || t.cpu < 0.05f) Ui.TEXT3 else Ui.load(t.cpu, 10f, 50f))
            }
            f.body.removeView(more)
            if (sorted.size > shown.size) {
                more.text = a.getString(R.string.d_more, sorted.size - shown.size)
                f.body.addView(more)
            }
        }

        fun load(clkTck: Long) {
            if (busy) return
            busy = true
            Sampler.exec(Inspect.threadsCommand(p.pid)) { out ->
                busy = false
                if (dlg?.isShowing != true) return@exec
                val list = Inspect.parseThreads(out.orEmpty())
                val now = SystemClock.elapsedRealtime()
                if (prevAt > 0) Inspect.applyCpu(list, prev, (now - prevAt) / 1000.0, clkTck)
                prev = list.associate { it.tid to it.ticks }
                prevAt = now
                last = list
                show(list)
            }
        }

        val live = Sampler.Listener { snap -> load(snap.clkTck) }
        val d = dialog(a, f)
        dlg = d
        d.setOnDismissListener { Sampler.remove(live) }
        d.show()
        Ui.glassDialog(d)
        copyButton(a, d, p) {
            last.takeIf { it.isNotEmpty() }?.sortedByDescending { if (it.cpu.isNaN()) -1f else it.cpu }?.joinToString("\n") {
                String.format(java.util.Locale.ROOT, "%7d  %c  %6s%%  %s", it.tid, it.state,
                    if (it.cpu.isNaN()) "-" else Fmt.pct(it.cpu), it.name)
            }
        }
        Sampler.add(live)   // сразу отдаёт последний замер — первый список без ожидания
    }

    /** Открытые файлы, сокеты и каналы; «Обновить» перечитывает список. */
    fun showFiles(a: MainActivity, p: ProcInfo) {
        val dp = a.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val f = frame(a, p)
        f.title.text = a.getString(R.string.f_title_short)
        val d = AlertDialog.Builder(a)
            .setView(ScrollView(a).apply { addView(f.box) })
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.refresh, null)
            .setPositiveButton(R.string.copy, null)
            .create()
        var last: List<Inspect.Fd> = emptyList()

        val names = mapOf(
            Inspect.Kind.FILE to R.string.f_files, Inspect.Kind.SOCKET to R.string.f_sockets,
            Inspect.Kind.PIPE to R.string.f_pipes, Inspect.Kind.DEVICE to R.string.f_devices,
            Inspect.Kind.OTHER to R.string.f_other,
        )

        fun show(fds: List<Inspect.Fd>) {
            f.body.removeAllViews()
            f.title.text = a.getString(R.string.f_title, fds.size)
            if (fds.isEmpty()) {
                f.body.addView(note(a, a.getString(R.string.f_none)))
                return
            }
            val groups = fds.groupBy { it.kind }
            f.body.addView(note(a, names.keys.filter { it in groups }
                .joinToString(" · ") { a.getString(names.getValue(it)) + " " + groups.getValue(it).size }).apply {
                setPadding(px(4f), px(4f), px(4f), 0)
            })
            for ((kind, name) in names) {
                val list = groups[kind] ?: continue
                f.body.addView(TextView(a).apply {
                    text = a.getString(name) + " · " + list.size
                    textSize = 13f; typeface = Ui.medium; setTextColor(Ui.primary)
                    setPadding(px(4f), px(14f), 0, px(4f))
                })
                // один выделяемый текст на раздел: у system_server дескрипторов больше тысячи
                val sb = SpannableStringBuilder()
                for (fd in list) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    val start = sb.length
                    sb.append(fd.fd.toString()).append("  ")
                    sb.setSpan(ForegroundColorSpan(Ui.TEXT3), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append(fd.socket ?: fd.target)
                }
                f.body.addView(TextView(a).apply {
                    text = sb; textSize = 13f; setTextColor(Ui.TEXT); setLineSpacing(0f, 1.15f)
                    setTextIsSelectable(true)
                    setPadding(px(4f), 0, px(4f), 0)
                })
            }
        }

        fun load() {
            Sampler.exec(Inspect.filesCommand(p.pid)) { out ->
                if (d.isShowing) { last = Inspect.parseFiles(out.orEmpty()); show(last) }
            }
        }

        f.body.addView(note(a, a.getString(R.string.ch_collecting)))
        d.show()
        Ui.glassDialog(d)
        // своя обработка, чтобы «Обновить» не закрывало диалог
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { load() }
        copyButton(a, d, p) {
            last.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.fd}  ${it.socket ?: it.target}" }
        }
        load()
    }
}
