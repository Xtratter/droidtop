package io.github.xtratter.droidtop

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Подробности о процессе и действия с ним. */
object ProcessDialog {
    fun show(a: MainActivity, p: ProcInfo, s: Snapshot) {
        val dp = a.resources.displayMetrics.density
        val details = TextView(a).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
            text = baseInfo(a, p, s)
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(details)
        }
        val dialog = AlertDialog.Builder(a)
            .setTitle(p.title)
            .setView(ScrollView(a).apply { addView(box) })
            .setNegativeButton(R.string.close, null)
            .create()

        fun action(text: Int, danger: Boolean = false, block: () -> Unit) {
            box.addView(Button(a, null, android.R.attr.borderlessButtonStyle).apply {
                setText(text)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isAllCaps = false
                if (danger) setTextColor(Palette.RED)
                setOnClickListener { dialog.dismiss(); block() }
            })
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
                when (k) {
                    "Uid" -> sb.append("\nUID: ").append(v.substringBefore(' '))
                    "VmHWM" -> sb.append("\n").append(a.getString(R.string.d_peak, v))
                    "VmSwap" -> sb.append("\n").append(a.getString(R.string.d_swap, v))
                }
            }
            parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { sb.append("\noom_score_adj: ").append(it) }
            details.append(sb)
        }
    }

    private fun baseInfo(a: MainActivity, p: ProcInfo, s: Snapshot): String {
        val age = (s.uptime - p.startTicks.toDouble() / s.clkTck).toLong().coerceAtLeast(0)
        val lines = ArrayList<String>()
        if (p.title != p.name) lines += p.name
        lines += a.getString(R.string.d_ids, p.pid, p.ppid, p.user.ifEmpty { "?" })
        lines += a.getString(R.string.d_state, p.state.toString(), a.getString(stateName(p.state)))
        lines += a.getString(R.string.d_threads, p.threads, p.nice, p.prio)
        lines += a.getString(R.string.d_cpu, Fmt.pct(p.cpu), Fmt.cpuTime(p.cpuTicks, s.clkTck))
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
                .show()
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
