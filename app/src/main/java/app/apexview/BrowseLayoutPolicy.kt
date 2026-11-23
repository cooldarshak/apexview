package app.apexview

/** Pure Material 3 responsive sizing shared by touch, D-pad and large-font layouts. */
internal object BrowseLayoutPolicy {
    private const val SIDE_PADDING_DP = 20f
    private const val GAP_DP = 12f

    fun topBarHeightDp(fontScale: Float): Float {
        val scale = fontScale.takeIf { it.isFinite() }?.coerceIn(1f, 2f) ?: 1f
        return 60f + (scale - 1f) * 16f
    }

    /** Keep a focusable card in view even on narrow screens and at 200% text. */
    fun cardWidthDp(availableWidthDp: Float, posters: Boolean, fontScale: Float): Float {
        val width = availableWidthDp.takeIf { it.isFinite() && it > 0f } ?: 320f
        val scale = fontScale.takeIf { it.isFinite() }?.coerceIn(1f, 2f) ?: 1f
        val available = (width - SIDE_PADDING_DP * 2f).coerceAtLeast(1f)
        val preferred = (if (posters) 150f else 250f) * (1f + (scale - 1f) * 0.35f)
        val columns = ((available + GAP_DP) / (preferred + GAP_DP)).toInt().coerceIn(1, if (posters) 7 else 5)
        val fitted = (available - GAP_DP * (columns - 1)) / columns
        return fitted.coerceIn(minOf(available, if (posters) 120f else 185f), minOf(available, 360f))
    }

    /** Landscape phone must never bury controls under a hard-coded 320dp artwork hero. */
    fun heroHeightDp(availableWidthDp: Float, screenHeightDp: Float, fontScale: Float): Float {
        val width = availableWidthDp.takeIf { it.isFinite() && it > 0f } ?: 360f
        val height = screenHeightDp.takeIf { it.isFinite() && it > 0f } ?: 640f
        val scale = fontScale.takeIf { it.isFinite() }?.coerceIn(1f, 2f) ?: 1f
        val minimum = minOf(320f * (1f + (scale - 1f) * 0.2f), height * 0.6f)
        return minOf(width * 9f / 16f, height * 0.62f).coerceAtLeast(minimum)
    }
}
