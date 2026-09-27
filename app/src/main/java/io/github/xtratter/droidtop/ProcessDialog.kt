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
            background = Ui.pill(a, 0x2EFFFFFF)
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

        // плитки с главными цифрами
        fun tile(label: Int, value: String, color: Int) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(a, 18f)
            setPadding(px(14f), px(10f), px(14f), px(12f))
            addView(TextView(a).apply { setText(label); textSize = 12f; setTextColor(Ui.TEXT2) })
            addView(TextView(a).apply { text = value; textSize = 18f; typeface = Ui.bold; setTextColor(color) })
        }
        fun row(vararg tiles: View) = LinearLayout(a).apply {
            tiles.forEachIndexed { i, t ->
                addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = px(8f) })
            }
        }
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(18f), 0, px(6f)) }
        grid.addView(row(
            tile(R.string.sort_cpu, Fmt.pct(p.cpu) + "%", Ui.load(p.cpu, 10f, 50f)),
            tile(R.string.sort_mem, Fmt.size(p.rss) + " · " + Fmt.pct(p.mem) + "%", Ui.TEXT),
        ))
        grid.addView(row(
            tile(R.string.sort_thr, p.threads.toString(), Ui.TEXT),
            tile(R.string.sort_time, Fmt.cpuTime(p.cpuTicks, s.clkTck), Ui.TEXT),
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        box.addView(grid)

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
                background = Ui.pill(a, 0x2EFFFFFF)
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

        val dialog = AlertDialog.Builder(a)
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton(R.string.close, null)
            .create()
        dlg = dialog

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
            if (s.root) action(R.string.act_force_stop, danger = true) {
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
