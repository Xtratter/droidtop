package io.github.xtratter.droidtop

/** Цвета в духе htop. */
object Palette {
    const val TEXT = 0xFFD0D0D0.toInt()
    const val DIM = 0xFF707070.toInt()
    const val BRIGHT = 0xFFFFFFFF.toInt()
    const val LABEL = 0xFF4FC3F7.toInt()
    const val GREEN = 0xFF66BB6A.toInt()
    const val YELLOW = 0xFFFFCA28.toInt()
    const val RED = 0xFFEF5350.toInt()
    const val BLUE = 0xFF42A5F5.toInt()
    const val MAGENTA = 0xFFCE93D8.toInt()
    const val CYAN = 0xFF80DEEA.toInt()
    const val HEADER_BG = 0xFF2E7D32.toInt()
    const val HEADER_SORT_BG = 0xFF26C6DA.toInt()
    const val HEADER_TEXT = 0xFF000000.toInt()

    fun load(v: Float, warn: Float, high: Float) = when {
        v >= high -> RED
        v >= warn -> YELLOW
        v > 0f -> TEXT
        else -> DIM
    }
}
