package io.github.xtratter.droidtop

import android.app.AlertDialog
import android.content.Intent
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Подробности о процессе и действия с ним. */
object ProcessDialog {
    private const val MAX_CHILDREN = 30

    fun show(a: MainActivity, p: ProcInfo, s: Snapshot) {
        val dp = a.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22f), px(22f), px(22f), px(8f))
        }

        // шапка: значок, название, имя процесса
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val icon = AppIcons.get(p.pkg)
        head.addView(if (icon != null) ImageView(a).apply { setImageBitmap(icon) } else TextView(a).apply {
            text = p.title.trimStart('[', '/', '.', '@').take(1).uppercase()
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = Ui.medium
            setTextColor(Ui.TEXT)
            background = Ui.pill(a, Ui.ink(0x2E))
        }, LinearLayout.LayoutParams(px(48f), px(48f)))
        head.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14f), 0, 0, 0)
            addView(TextView(a).apply {
                text = p.title; textSize = 20f; typeface = Ui.medium; setTextColor(Ui.TEXT)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(a).apply {
                text = if (p.title != p.name) p.name else a.getString(R.string.row_sub, p.pid, p.user.ifEmpty { "?" }, a.getString(stateName(p.state)))
                textSize = 13f; setTextColor(Ui.TEXT2); maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(head)

        // плитки с главными цифрами — обновляются с каждым замером, пока диалог открыт
        fun value() = TextView(a).apply { textSize = 18f; typeface = Ui.bold; setTextColor(Ui.TEXT) }
        val cpuV = value()
        val memV = value()
        val thrV = value()
        val timeV = value()
        fun tile(label: Int, v: TextView) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(a, 18f)
            setPadding(px(14f), px(10f), px(14f), px(12f))
            addView(TextView(a).apply { setText(label); textSize = 12f; setTextColor(Ui.TEXT2) })
            addView(v)
        }
        fun setText(v: TextView, text: String) { if (v.text.toString() != text) v.text = text }
        fun fillTiles(q: ProcInfo, snap: Snapshot) {
            setText(cpuV, Fmt.pct(q.cpu) + "%"); cpuV.setTextColor(Ui.load(q.cpu, 10f, 50f))
            setText(memV, Fmt.size(q.rss) + " · " + Fmt.pct(q.mem) + "%")
            setText(thrV, q.threads.toString())
            setText(timeV, Fmt.cpuTime(q.cpuTicks, snap.clkTck))
        }
        fillTiles(p, s)
        fun row(vararg tiles: View) = LinearLayout(a).apply {
            tiles.forEachIndexed { i, t ->
                addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = px(8f) })
            }
        }
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(18f), 0, px(6f)) }
        grid.addView(row(
            tile(R.string.sort_cpu, cpuV),
            tile(R.string.sort_mem, memV),
        ))
        grid.addView(row(
            tile(R.string.sort_thr, thrV),
            tile(R.string.sort_time, timeV),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        box.addView(grid)

        // история процесса — обновляется, пока диалог открыт
        val cpuChart = ChartView(a, a.getString(R.string.ch_proc_cpu)).apply {
            chart.autoMax = true; chart.maxY = 5f
        }
        val memChart = ChartView(a, a.getString(R.string.ch_proc_mem)).apply {
            chart.autoMax = true; chart.maxY = 1f; chart.color = Ui.tertiary
            chart.format = { Human.size(a, (it * 1048576).toLong()) }
        }
        fun fillCharts() {
            val h = History.proc(p) ?: return
            cpuChart.chart.values = h.cpu.toArray(); cpuChart.invalidate()
            memChart.chart.values = h.rss.toArray(); memChart.invalidate()
        }
        fillCharts()
        box.addView(cpuChart, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        box.addView(memChart)
        // задаётся ниже, когда готов блок подробностей
        var onSample: (ProcInfo, Snapshot) -> Unit = { _, _ -> }
        val live = Sampler.Listener { snap ->
            // тот же процесс в новом замере (PID мог достаться другому — сверяем время старта)
            snap.procs.firstOrNull { it.pid == p.pid && it.startTicks == p.startTicks }?.let { onSample(it, snap) }
            fillCharts()
        }

        // родитель и потомки — нажатие открывает их карточку
        var dlg: AlertDialog? = null
        fun section(text: String) = box.addView(TextView(a).apply {
            this.text = text; textSize = 13f; typeface = Ui.medium; setTextColor(Ui.primary)
            setPadding(px(4f), px(14f), 0, px(6f))
        })
        fun link(q: ProcInfo, up: Boolean) = box.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GlassDrawable(a, 16f)
            foreground = Ui.ripple(a, 16f)
            setPadding(px(10f), px(8f), px(14f), px(8f))
            val ic = AppIcons.get(q.pkg)
            addView(if (ic != null) ImageView(a).apply { setImageBitmap(ic) } else TextView(a).apply {
                text = q.title.trimStart('[', '/', '.', '@').take(1).uppercase()
                gravity = Gravity.CENTER; textSize = 13f; typeface = Ui.medium; setTextColor(Ui.TEXT)
                background = Ui.pill(a, Ui.ink(0x2E))
            }, LinearLayout.LayoutParams(px(30f), px(30f)))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12f), 0, px(8f), 0)
                addView(TextView(a).apply {
                    text = (if (up) "↑ " else "") + q.title
                    textSize = 14f; setTextColor(Ui.TEXT); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(a).apply {
                    text = a.getString(R.string.row_sub, q.pid, q.user.ifEmpty { "?" }, Fmt.size(q.rss))
                    textSize = 12f; setTextColor(Ui.TEXT2); maxLines = 1
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(a).apply {
                text = Fmt.pct(q.cpu) + "%"; textSize = 13f; typeface = Ui.bold
                setTextColor(if (q.cpu < 0.05f) Ui.TEXT3 else Ui.load(q.cpu, 10f, 50f))
            })
            setOnClickListener { dlg?.dismiss(); a.openProcess(q) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })

        s.procs.firstOrNull { it.pid == p.ppid && p.ppid != p.pid }?.let { parent ->
            section(a.getString(R.string.d_parent))
            link(parent, true)
        }
        val children = s.procs.filter { it.ppid == p.pid && it.pid != p.pid }.sortedByDescending { it.cpu }
        if (children.isNotEmpty()) {
            section(a.getString(R.string.d_children, children.size))
            children.take(MAX_CHILDREN).forEach { link(it, false) }
            if (children.size > MAX_CHILDREN) box.addView(TextView(a).apply {
                text = a.getString(R.string.d_more, children.size - MAX_CHILDREN)
                textSize = 12f; setTextColor(Ui.TEXT3); setPadding(px(4f), px(6f), 0, 0)
            })
        }

        val details = TextView(a).apply {
            textSize = 13f
            setTextColor(Ui.TEXT2)
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
            text = baseInfo(a, p, s)
            setPadding(px(4f), px(10f), px(4f), px(10f))
        }
        box.addView(details)
        var extra = ""      // строки, дочитанные из /proc/PID/status и cmdline
        onSample = { q, snap ->
            fillTiles(q, snap)
            val t = baseInfo(a, q, snap) + extra
            // пока пользователь выделяет текст (например, чтобы скопировать команду), не трогаем его
            if (!details.hasSelection() && details.text.toString() != t) details.text = t
        }

        val dialog = AlertDialog.Builder(a)
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton(R.string.close, null)
            .create()
        dlg = dialog
        Sampler.add(live)
        dialog.setOnDismissListener { Sampler.remove(live) }

        // действия — тональные кнопки-«пилюли»
        fun action(text: Int, danger: Boolean = false, block: () -> Unit) {
            val color = if (danger) Ui.HOT else Ui.primary
            box.addView(TextView(a).apply {
                setText(text)
                gravity = Gravity.CENTER
                textSize = 15f
                typeface = Ui.medium
                setTextColor(color)
                background = Ui.pill(a, Ui.withAlpha(color, 0.14f), Ui.withAlpha(color, 0.3f))
                foreground = Ui.ripple(a, 100f)
                setOnClickListener { dialog.dismiss(); block() }
            }, LinearLayout.LayoutParams(-1, px(48f)).apply { topMargin = px(8f) })
        }

        a.branch(p.pid)?.let { b ->
            action(if (b.collapsed) R.string.act_expand else R.string.act_collapse) { a.toggleBranch(p.pid) }
        }
        action(R.string.act_term) { signal(a, p, "TERM") }
        action(R.string.act_kill, danger = true) { signal(a, p, "KILL") }
        if (p.state == 'T') action(R.string.act_cont) { signal(a, p, "CONT") }
        else action(R.string.act_stop) { signal(a, p, "STOP") }
        val pkg = p.pkg
        if (pkg != null) {
            if (s.full) action(R.string.act_force_stop, danger = true) {
                run(a, "am force-stop $pkg", a.getString(R.string.done_force_stop, p.label ?: pkg))
            }
            action(R.string.act_app_info) {
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
            }
        }
        dialog.show()
        Ui.glassDialog(dialog)

        // полная командная строка и то, чего нет в /proc/PID/stat
        val dir = "/proc/${p.pid}"
        Sampler.exec("tr '\\000' ' ' < $dir/cmdline; echo; echo @; grep -E '^(Uid|VmHWM|VmSwap):' $dir/status; echo @; cat $dir/oom_score_adj") { out ->
            if (out == null || !dialog.isShowing) return@exec
            val parts = out.split("\n@\n").map { it.trim() }
            val sb = StringBuilder()
            parts.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { sb.append("\n").append(a.getString(R.string.d_cmdline, it)) }
            parts.getOrNull(1)?.lines()?.forEach { l ->
                val k = l.substringBefore(':')
                val v = l.substringAfter(':').trim().replace(Regex("\\s+"), " ")
                val kb = v.substringBefore(' ').toLongOrNull()
                val human = if (kb != null && v.endsWith("kB")) Human.size(a, kb * 1024) else v
                when (k) {
                    "Uid" -> sb.append("\nUID: ").append(v.substringBefore(' '))
                    "VmHWM" -> sb.append("\n").append(a.getString(R.string.d_peak, human))
                    "VmSwap" -> sb.append("\n").append(a.getString(R.string.d_swap, human))
                }
            }
            parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { sb.append("\noom_score_adj: ").append(it) }
            extra = sb.toString()
            details.append(sb)
        }
    }

    private fun baseInfo(a: MainActivity, p: ProcInfo, s: Snapshot): String {
        val age = (s.uptime - p.startTicks.toDouble() / s.clkTck).toLong().coerceAtLeast(0)
        val lines = ArrayList<String>()
        lines += a.getString(R.string.d_ids, p.pid, p.ppid, p.user.ifEmpty { "?" })
        lines += a.getString(R.string.d_state, p.state.toString(), a.getString(stateName(p.state)))
        lines += a.getString(R.string.d_threads, p.threads, p.nice, p.prio)
        lines += a.getString(R.string.d_mem, Fmt.size(p.rss), Fmt.pct(p.mem), Fmt.size(p.vsize))
        lines += a.getString(R.string.d_started, duration(a, age))
        if (p.kernel) lines += a.getString(R.string.d_kernel)
        return lines.joinToString("\n")
    }

    private fun stateName(c: Char) = when (c) {
        'R' -> R.string.st_running
        'S' -> R.string.st_sleeping
        'D' -> R.string.st_disk
        'Z' -> R.string.st_zombie
        'T', 't' -> R.string.st_stopped
        'I' -> R.string.st_idle
        else -> R.string.st_other
    }

    private fun duration(a: MainActivity, sec: Long): String = when {
        sec < 60 -> a.getString(R.string.dur_s, sec)
        sec < 3600 -> a.getString(R.string.dur_m, sec / 60)
        sec < 86400 -> a.getString(R.string.dur_hm, sec / 3600, sec / 60 % 60)
        else -> a.getString(R.string.dur_dh, sec / 86400, sec / 3600 % 24)
    }

    private fun signal(a: MainActivity, p: ProcInfo, sig: String) {
        val system = p.kernel || p.user == "root" || p.user == "system" || p.pid < 1000
        val go = { run(a, "kill -$sig ${p.pid}", a.getString(R.string.done_signal, "SIG$sig", p.pid)) }
        if (system && sig != "CONT") {
            AlertDialog.Builder(a)
                .setTitle(p.title)
                .setMessage(R.string.warn_system)
                .setPositiveButton(R.string.do_it) { _, _ -> go() }
                .setNegativeButton(android.R.string.cancel, null)
                .show().also { Ui.glassDialog(it) }
        } else go()
    }

    private fun run(a: MainActivity, cmd: String, okText: String) {
        Sampler.exec("$cmd 2>&1; echo \"=\$?\"") { out ->
            val lines = out?.trim()?.lines().orEmpty()
            val ok = lines.lastOrNull() == "=0"
            val msg = if (ok) okText
            else a.getString(R.string.failed, lines.dropLast(1).joinToString(" ").ifBlank { a.getString(R.string.no_access) })
            Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
            Sampler.refreshNow()
        }
    }
}
