package io.github.xtratter.droidtop

/**
 * Тема оформления, в порядке списка в окне «Тема» (как в AppShelf). [SYSTEM] — тёмная или светлая, как в настройках
 * Android; [STANDARD] — тёмная (имя в настройках прежнее, чтобы выбранная тема сохранилась); [HTOP] — своя для DroidTop.
 */
enum class Theme(val title: Int) {
    SYSTEM(R.string.th_system),
    LIGHT(R.string.th_light),
    STANDARD(R.string.th_standard),
    GRAPHITE(R.string.th_graphite),
    AMOLED(R.string.th_amoled),
    HTOP(R.string.th_htop);

    companion object {
        /** Тема по умолчанию — тёмная или светлая, как в настройках Android. */
        val DEFAULT: Theme get() = SYSTEM
    }
}
