package io.github.xtratter.droidtop

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import java.util.concurrent.ConcurrentHashMap

/** Значки приложений, заранее отрисованные в маленькие картинки (загружаются в фоновом потоке). */
object AppIcons {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    fun get(pkg: String?): Bitmap? = pkg?.let { cache[it] }

    /** Загрузить недостающие значки; true — что-то добавилось. */
    fun load(ctx: Context, pkgs: Collection<String>): Boolean {
        val size = Ui.dp(ctx, 40f).toInt()
        val pm = ctx.packageManager
        var added = false
        for (pkg in pkgs) {
            if (cache.containsKey(pkg) || pkg in missing) continue
            try {
                val d = pm.getApplicationIcon(pkg)
                val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                d.draw(Canvas(b))
                cache[pkg] = b
                added = true
            } catch (e: PackageManager.NameNotFoundException) {
                missing += pkg
            }
        }
        return added
    }
}
