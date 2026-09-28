package io.github.xtratter.droidtop

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Единый источник данных для экрана и оверлея.
 * Работает в своём потоке и опрашивает систему, пока есть хотя бы один слушатель.
 */
object Sampler {
    fun interface Listener {
        fun onSnapshot(s: Snapshot)

        /** Нужны ли слушателю имена и значки всех процессов (оверлею хватает самых активных). */
        val needsAllProcs: Boolean get() = true
    }

    /** Сколько самых активных процессов подписывать, когда весь список никому не нужен. */
    private const val TOP_NAMED = 24

    private lateinit var app: Context
    private lateinit var prefs: Prefs
    private lateinit var handler: Handler
    private lateinit var parser: ProcParser
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val labels = HashMap<String, String?>()         // пакет → название (null — не приложение)
    private var shell: Shell? = null
    private var ticking = false

    /** Последний замер (читать только из главного потока). */
    var last: Snapshot? = null
        private set

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = Prefs(app)
        parser = ProcParser(Os.sysconf(OsConstants._SC_PAGESIZE), Os.sysconf(OsConstants._SC_CLK_TCK))
        handler = Handler(HandlerThread("sampler").apply { start() }.looper)
    }

    fun add(l: Listener) {
        if (!listeners.addIfAbsent(l)) return
        last?.let { l.onSnapshot(it) }
        handler.post { if (!ticking) { ticking = true; tick.run() } }
    }

    fun remove(l: Listener) {
        listeners -= l
    }

    /** Обновить прямо сейчас (например, после смены настроек или завершения процесса). */
    fun refreshNow() = handler.post {
        handler.removeCallbacks(tick)
        if (listeners.isNotEmpty()) { ticking = true; tick.run() }
    }

    /** Сменить режим доступа. [done] получает true, если оболочка открыта в нужном режиме. */
    fun setAccess(access: Access, done: (Boolean) -> Unit) = handler.post {
        shell?.close()
        shell = null
        openShell(access)
        labels.clear()
        val ok = shell?.access == access
        main.post { done(ok) }
        refreshNow()
    }

    /** Выполнить команду в текущей оболочке (с root или Shizuku, если они включены). */
    fun exec(cmd: String, done: (String?) -> Unit) = handler.post {
        val out = openShell()?.run(cmd)
        main.post { done(out) }
    }

    private fun openShell(want: Access = prefs.access()): Shell? {
        shell?.let { return it }
        shell = (if (want != Access.USER) Shell.open(want) else null) ?: Shell.open(Access.USER)
        parser.reset()
        main.post { History.clearProcs() }
        return shell
    }

    private val tick = object : Runnable {
        override fun run() {
            if (listeners.isEmpty()) { ticking = false; return }
            val primed = parser.primed
            val s = sample()
            if (s != null) {
                main.post {
                    last = s
                    History.add(s)
                    listeners.forEach { it.onSnapshot(s) }
                }
                // значки приложений грузим после показа, чтобы не задерживать первый кадр
                if (listeners.any { it.needsAllProcs }) {
                    val pkgs = s.procs.mapNotNullTo(HashSet()) { it.pkg }
                    if (AppIcons.load(app, pkgs)) main.post { listeners.forEach { it.onSnapshot(s) } }
                }
            }
            // первый замер не даёт загрузку CPU — второй делаем быстро
            val delay = if (primed) prefs.intervalMs.toLong() else 600L
            handler.postDelayed(this, delay)
        }
    }

    private fun sample(): Snapshot? {
        val sh = openShell() ?: return null
        val out = sh.runLines(parser.script())
        if (out == null) { shell = null; return null }

        val am = app.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val snap = parser.parse(
            out, sh.access, SystemClock.elapsedRealtime() / 1000.0,
            longArrayOf(mi.totalMem, mi.availMem),
        )
        snap.battery = try { Battery.read(app) } catch (e: Exception) { null }
        snap.interval = prefs.intervalMs / 1000f

        // только оверлей: полные имена и названия нужны лишь самым активным процессам
        val named = if (listeners.any { it.needsAllProcs }) snap.procs
        else snap.procs.sortedByDescending { it.cpu }.take(TOP_NAMED)
        val missing = parser.missingNames(named)
        val psOut = if (missing.isNotEmpty()) sh.run(parser.psCommand(missing)) else null
        parser.applyNames(psOut, snap.procs, missing)

        val useLabels = prefs.appLabels
        val pm = app.packageManager
        for (p in named) {
            val pkg = p.name.substringBefore(':')
            val label = if ('.' in pkg && '/' !in pkg) labelOf(pm, pkg) else null
            p.pkg = if (label != null) pkg else null
            p.label = label
            p.title = if (useLabels && label != null) label + p.name.substring(pkg.length) else p.name
        }
        return snap
    }

    private fun labelOf(pm: PackageManager, pkg: String): String? = labels.getOrPut(pkg) {
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
