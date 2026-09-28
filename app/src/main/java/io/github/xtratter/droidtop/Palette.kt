package io.github.xtratter.droidtop

/** Цвета в духе htop (в светлой теме — более тёмные оттенки, чтобы читались на светлом). */
object Palette {
    var TEXT = 0xFFD0D0D0.toInt(); private set
    var DIM = 0xFF707070.toInt(); private set
    var BRIGHT = 0xFFFFFFFF.toInt(); private set
    var LABEL = 0xFF4FC3F7.toInt(); private set
    var GREEN = 0xFF66BB6A.toInt(); private set
    var YELLOW = 0xFFFFCA28.toInt(); private set
    var RED = 0xFFEF5350.toInt(); private set
    var BLUE = 0xFF42A5F5.toInt(); private set
    var MAGENTA = 0xFFCE93D8.toInt(); private set
    var CYAN = 0xFF80DEEA.toInt(); private set
    const val HEADER_BG = 0xFF2E7D32.toInt()
    const val HEADER_SORT_BG = 0xFF26C6DA.toInt()
    const val HEADER_TEXT = 0xFF000000.toInt()

    fun apply(light: Boolean) {
        if (light) {
            TEXT = 0xFF303030.toInt(); DIM = 0xFF8A8A8A.toInt(); BRIGHT = 0xFF000000.toInt()
            LABEL = 0xFF0277BD.toInt(); GREEN = 0xFF2E7D32.toInt(); YELLOW = 0xFFA66F00.toInt()
            RED = 0xFFC62828.toInt(); BLUE = 0xFF1565C0.toInt(); MAGENTA = 0xFF8E24AA.toInt(); CYAN = 0xFF00838F.toInt()
        } else {
            TEXT = 0xFFD0D0D0.toInt(); DIM = 0xFF707070.toInt(); BRIGHT = 0xFFFFFFFF.toInt()
            LABEL = 0xFF4FC3F7.toInt(); GREEN = 0xFF66BB6A.toInt(); YELLOW = 0xFFFFCA28.toInt()
            RED = 0xFFEF5350.toInt(); BLUE = 0xFF42A5F5.toInt(); MAGENTA = 0xFFCE93D8.toInt(); CYAN = 0xFF80DEEA.toInt()
        }
    }

    fun load(v: Float, warn: Float, high: Float) = when {
        v >= high -> RED
        v >= warn -> YELLOW
        v > 0f -> TEXT
        else -> DIM
    }
}
