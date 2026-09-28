package io.github.xtratter.droidtop

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/** Плавающий оверлей поверх всех окон. Работает как служба переднего плана с уведомлением. */
class OverlayService : Service(), Sampler.Listener {
    private lateinit var wm: WindowManager
    private lateinit var view: OverlayView
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var prefs: Prefs

    override fun onBind(intent: Intent?): IBinder? = null

    // оверлей нужен только на включённом экране — при выключенном опрос полностью останавливаем
    override val needsAllProcs get() = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) Sampler.remove(this@OverlayService)
            else Sampler.add(this@OverlayService)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startInForeground()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(WindowManager::class.java)
        Ui.init(this)
        view = newView()
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.overlayX
            y = prefs.overlayY
        }
        applyPrefs()
        wm.addView(view, lp)
        instance = this
        Sampler.init(this)
        Sampler.add(this)
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            unregisterReceiver(screen)
            Sampler.remove(this)
            wm.removeView(view)
        }
        super.onDestroy()
    }

    override fun onSnapshot(s: Snapshot) {
        view.snapshot = s
    }

    private var builtScale = 0
    private var builtWidth = 0

    private fun newView(): OverlayView {
        builtScale = prefs.overlayScale
        builtWidth = prefs.overlayWidth
        return OverlayView(this, builtScale / 100f, builtWidth).also { v ->
            setupTouch(v)
            Sampler.last?.let { v.snapshot = it }
        }
    }

    /** Создать плашку заново — после смены темы, размера или ширины. */
    fun rebuild() {
        if (!::view.isInitialized) return
        val old = view
        view = newView()
        applyPrefs()
        if (old.isAttachedToWindow) { wm.removeView(old); wm.addView(view, lp) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // тема «как в системе»: переключили тёмный режим — перекрашиваемся
        val t = prefs.theme()
        if (!Ui.isCurrent(this, t)) { Ui.apply(this, t); rebuild() }
    }

    /** Применить настройки оверлея (вызывается и после их изменения). */
    fun applyPrefs() {
        if (prefs.overlayScale != builtScale || prefs.overlayWidth != builtWidth) return rebuild()
        view.parts = prefs.overlayParts
        view.topByMem = prefs.overlayTopMem
        view.topCount = prefs.overlayTop
        view.kernelThreads = prefs.kernelThreads
        val through = prefs.overlayClickThrough
        view.bgAlpha = prefs.overlayAlpha / 100f
        lp.flags = if (through) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        // Android 12+ пропускает касания сквозь чужое окно, только если оно прозрачнее 80 %
        lp.alpha = if (through) 0.8f else 1f
        if (view.isAttachedToWindow) wm.updateViewLayout(view, lp)
        view.requestLayout()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch(view: OverlayView) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !prefs.overlayLock && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).toInt().coerceAtLeast(0)
                        lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        wm.updateViewLayout(view, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                        // закреплённый оверлей: провели пальцем — не открываем приложение
                    } else if (dragging) {
                        prefs.overlayX = lp.x; prefs.overlayY = lp.y
                    } else {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            true
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.overlay), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.overlay_on))
            .setContentText(getString(R.string.overlay_hint))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.close), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "stop"

        var instance: OverlayService? = null
            private set

        val running get() = instance != null

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, OverlayService::class.java))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, OverlayService::class.java))
    }
}
