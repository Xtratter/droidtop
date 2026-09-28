package io.github.xtratter.droidtop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/** Копирование в буфер обмена. */
object Clip {
    fun copy(ctx: Context, label: String, text: CharSequence) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ сам показывает, что скопировано
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
