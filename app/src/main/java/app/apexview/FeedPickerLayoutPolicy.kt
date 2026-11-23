package app.apexview

/** Adaptive feed picker width contract for phone and D-pad TV. */
internal object FeedPickerLayoutPolicy {
    private const val CARD_WIDTH_DP = 120f
    private const val SPACING_DP = 10f
    private const val SIDE_PADDING_DP = 20f
    private const val MAX_COLUMNS = 8

    fun columns(availableWidthDp: Float): Int {
        if (!availableWidthDp.isFinite()) return 1
        val usable = (availableWidthDp - 2f * SIDE_PADDING_DP).coerceAtLeast(0f)
        return ((usable + SPACING_DP) / (CARD_WIDTH_DP + SPACING_DP))
            .toInt().coerceIn(1, MAX_COLUMNS)
    }
}
