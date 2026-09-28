package io.github.xtratter.droidtop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Запись статистики выбранного приложения в CSV в «Загрузки/DroidTop».
 * Служба переднего плана: пишет, пока её не остановят, даже когда DroidTop закрыт.
 */
class RecordService : Service(), Sampler.Listener {
    private lateinit var target: Record.Target
    private val stats = Record.Stats()
    private val io = Executors.newSingleThreadExecutor()
    private var out: OutputStream? = null
    private var uri: Uri? = null
    private var shownPath = ""
    private var startedAt = 0L
    private lateinit var power: PowerManager
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (current != null) return START_NOT_STICKY        // уже пишем — вторую запись не начинаем
        val label = intent?.getStringExtra(EXTRA_LABEL)
        val name = intent?.getStringExtra(EXTRA_NAME)
        if (label == null || name == null) { stopSelf(); return START_NOT_STICKY }
        target = Record.Target(intent.getStringExtra(EXTRA_PKG), name, label)
        power = getSystemService(PowerManager::class.java)
        startInForeground(getString(R.string.rec_starting))
        if (!openFile()) { stopSelf(); return START_NOT_STICKY }
        current = target
        startedAt = SystemClock.elapsedRealtime()
        Sampler.init(this)
        Sampler.add(this)
        return START_NOT_STICKY
    }

    /** Файл в «Загрузки/DroidTop» (Android 10+ — через MediaStore, без разрешений). */
    private fun openFile(): Boolean = try {
        val name = Record.fileName(target.label, SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()))
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DroidTop")
            }
            val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            uri = u
            out = contentResolver.openOutputStream(u, "w")
            shownPath = Environment.DIRECTORY_DOWNLOADS + "/DroidTop/" + name
        } else {
            val f = File(getExternalFilesDir(null), name)
            out = f.outputStream()
            shownPath = f.path
        }
        write(Record.HEADER)
        true
    } catch (e: Exception) {
        false
    }

    private fun write(line: String) {
        val o = out ?: return
        io.execute { try { o.write((line + "\n").toByteArray()); o.flush() } catch (_: Exception) {} }
    }

    private var lastSnap: Snapshot? = null

    override fun onSnapshot(s: Snapshot) {
        // тот же замер приходит повторно, когда догрузились значки приложений
        if (current == null || s === lastSnap) return
        lastSnap = s
        val a = Record.sample(target, s)
        stats.add(a)
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        write(Record.row(timeFmt.format(Date()), elapsed, a, s, power.isInteractive))
        // уведомление обновляем не на каждом замере
        if (stats.samples % 5 == 1) {
            val now = if (a.procs == 0) getString(R.string.rec_not_running)
            else getString(R.string.rec_now, Fmt.pct(a.cpu), Human.size(this, a.rss))
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID,
                notification(getString(R.string.rec_progress, stats.samples, duration((elapsed).toLong())) + " · " + now))
        }
    }

    override fun onDestroy() {
        Sampler.remove(this)
        if (current != null) {
            current = null
            val o = out
            io.execute { try { o?.close() } catch (_: Exception) {} }
            showSaved()
        }
        io.shutdown()
        super.onDestroy()
    }

    private fun duration(sec: Long) =
        if (sec < 3600) String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
        else String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)

    /** Итоги — отдельным уведомлением; нажатие открывает файл. */
    private fun showSaved() {
        val text = if (stats.running == 0) getString(R.string.rec_saved_empty, stats.samples)
        else getString(R.string.rec_saved_stats, stats.samples, Fmt.pct(stats.cpuAvg), Fmt.pct(stats.cpuMax),
            Human.size(this, stats.rssAvg), Human.size(this, stats.rssMax))
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.rec_saved, target.label))
            .setContentText(shownPath)
            .setStyle(Notification.BigTextStyle().bigText(shownPath + "\n" + text))
            .setAutoCancel(true)
        uri?.let {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(it, "text/csv")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setContentIntent(PendingIntent.getActivity(this, 3, Intent.createChooser(view, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
        }
        getSystemService(NotificationManager::class.java).notify(SAVED_ID, b.build())
        lastSaved = shownPath
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.rec_title, target.label))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.rec_stop_short), stop).build())
            .build()
    }

    private fun startInForeground(text: String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.rec_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    companion object {
        private const val CHANNEL = "record"
        private const val NOTIF_ID = 2
        private const val SAVED_ID = 3
        private const val ACTION_STOP = "stop"
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LABEL = "label"

        /** Что пишем сейчас; null — запись не идёт. */
        var current: Record.Target? = null
            private set
        /** Путь последнего сохранённого файла — для подсказки. */
        var lastSaved: String? = null
            private set

        fun start(ctx: Context, p: ProcInfo) {
            ctx.startForegroundService(Intent(ctx, RecordService::class.java)
                .putExtra(EXTRA_PKG, p.pkg)
                .putExtra(EXTRA_NAME, p.name)
                .putExtra(EXTRA_LABEL, p.label ?: p.title))
        }

        fun stop(ctx: Context) = ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP))
    }
}
