package io.github.xtratter.droidtop

import android.content.Context
import android.graphics.Canvas
import android.view.View

/** Одна строка списка процессов, рисуется вручную — так быстро и компактно. */
class ProcRowView(ctx: Context, private val table: Table) : View(ctx) {
    var proc: ProcInfo? = null
    var clkTck = 100L
    var ownPid = android.os.Process.myPid()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), table.rowH)
    }

    override fun onDraw(c: Canvas) {
        val p = proc ?: return
        table.drawRow(c, { col ->
            when (col) {
                Col.PID -> p.pid.toString()
                Col.USER -> p.user
                Col.NI -> p.nice.toString()
                Col.S -> p.state.toString()
                Col.CPU -> Fmt.pct(p.cpu)
                Col.MEM -> Fmt.pct(p.mem)
                Col.RES -> Fmt.size(p.rss)
                Col.THR -> p.threads.toString()
                Col.TIME -> Fmt.cpuTime(p.cpuTicks, clkTck)
                Col.CMD -> p.title
            }
        }, { col ->
            when (col) {
                Col.S -> when (p.state) {
                    'R' -> Palette.GREEN
                    'D' -> Palette.RED
                    'Z', 'T', 't' -> Palette.MAGENTA
                    else -> Palette.DIM
                }
                Col.CPU -> Palette.load(p.cpu, 10f, 50f)
                Col.MEM -> Palette.load(p.mem, 3f, 10f)
                Col.NI -> if (p.nice < 0) Palette.RED else if (p.nice > 0) Palette.GREEN else Palette.DIM
                Col.USER -> if (p.user == "root") Palette.YELLOW else Palette.TEXT
                Col.TIME, Col.THR -> Palette.DIM
                Col.CMD -> when {
                    p.pid == ownPid -> Palette.GREEN
                    p.kernel -> Palette.DIM
                    p.pkg != null -> Palette.CYAN
                    else -> Palette.BRIGHT
                }
                else -> Palette.TEXT
            }
        })
    }
}
