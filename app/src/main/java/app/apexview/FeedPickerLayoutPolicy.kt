package app.apexview

/** Adaptive feed picker width contract for phone and D-pad TV. */
internal object FeedPickerLayoutPolicy {
    private const val CARD_WIDTH_DP = 120f
    private const val SPACING_DP = 10f
    private const val SIDE_PADDING_DP = 20f
    private const val MAX_COLUMNS = 8

    fun cardWidthDp(availableWidthDp: Float, fontScale: Float = 1f): Float {
        val scale = fontScale.takeIf { it.isFinite() }?.coerceIn(1f, 2f) ?: 1f
        val wanted = CARD_WIDTH_DP * (1f + (scale - 1f) * 0.4f)
        val width = availableWidthDp.takeIf { it.isFinite() && it > 0f } ?: 160f
        return minOf(wanted, (width - 2f * SIDE_PADDING_DP).coerceAtLeast(1f))
    }

    fun columns(availableWidthDp: Float, fontScale: Float = 1f): Int {
        if (!availableWidthDp.isFinite() || availableWidthDp <= 0f) return 1
        val usable = (availableWidthDp - 2f * SIDE_PADDING_DP).coerceAtLeast(0f)
        val card = cardWidthDp(availableWidthDp, fontScale)
        return ((usable + SPACING_DP) / (card + SPACING_DP))
            .toInt().coerceIn(1, MAX_COLUMNS)
    }
}
