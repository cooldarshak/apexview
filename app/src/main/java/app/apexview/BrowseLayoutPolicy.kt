package app.apexview

/** Pure Material 3 responsive sizing shared by touch, D-pad and large-font layouts. */
internal object BrowseLayoutPolicy {
    private const val SIDE_PADDING_DP = 20f
    private const val GAP_DP = 12f

    fun compactPortrait(isPortrait: Boolean, widthDp: Float): Boolean =
        isPortrait && widthDp.isFinite() && widthDp in 1f..719.999f

    fun topBarHeightDp(fontScale: Float): Float {
        val scale = fontScale.takeIf { it.isFinite() }?.coerceIn(1f, 2f) ?: 1f
        return 60f + (scale - 1f) * 16f
    }

    /** Show two portrait cards plus a peek of the next; respect 200% accessibility type. */
    fun cardWidthDp(availableWidthDp: Float, posters: Boolean, fontScale: Float,
                    portrait: Boolean = false): Float {
        val width = availableWidthDp.takeIf { it.isFinite() && it > 0f } ?: 320f
        val scale = fontScale.takeIf { it.isFinite() }?.coerceIn(1f, 2f) ?: 1f
        val available = (width - SIDE_PADDING_DP * 2f).coerceAtLeast(1f)
        if (compactPortrait(portrait, width)) {
            val preferredColumns = if (scale >= 1.75f || width < 320f) 1 else
                ((available + GAP_DP) / (162f + GAP_DP)).toInt().coerceIn(2, 4)
            val minReadableWidth = if (scale >= 1.5f) 142f else if (posters) 110f else 126f
            val maxFittingColumns = ((available + GAP_DP) / (minReadableWidth + GAP_DP)).toInt().coerceAtLeast(1)
            val columns = minOf(preferredColumns, maxFittingColumns)
            val exact = (available - GAP_DP * (columns - 1)) / columns
            if (columns == 1) return available
            // Leave the beginning of the next card visible as a scrolling cue.
            val peek = (available - GAP_DP * columns) / (columns + 0.12f)
            return minOf(exact, maxOf(minReadableWidth, peek))
        }
        // Original landscape/TV sizing is deliberately unchanged.
        val preferred = (if (posters) 150f else 250f) * (1f + (scale - 1f) * 0.35f)
        val columns = ((available + GAP_DP) / (preferred + GAP_DP)).toInt().coerceIn(1, if (posters) 7 else 5)
        val fitted = (available - GAP_DP * (columns - 1)) / columns
        return fitted.coerceIn(minOf(available, if (posters) 120f else 185f), minOf(available, 360f))
    }

    /** Compact normal-size portrait hero; grow it for accessibility text. */
    fun heroHeightDp(availableWidthDp: Float, screenHeightDp: Float, fontScale: Float,
                     portrait: Boolean = false): Float {
        val width = availableWidthDp.takeIf { it.isFinite() && it > 0f } ?: 360f
        val height = screenHeightDp.takeIf { it.isFinite() && it > 0f } ?: 640f
        val scale = fontScale.takeIf { it.isFinite() }?.coerceIn(1f, 2f) ?: 1f
        if (compactPortrait(portrait, width)) {
            return minOf(height * 0.73f, 288f + 118f * (scale - 1f))
                .coerceAtLeast(minOf(240f, height * 0.60f))
        }
        val minimum = minOf(320f * (1f + (scale - 1f) * 0.2f), height * 0.6f)
        return minOf(width * 9f / 16f, height * 0.62f).coerceAtLeast(minimum)
    }

    fun sectionGapDp(isPortrait: Boolean, widthDp: Float): Float =
        if (compactPortrait(isPortrait, widthDp)) 18f else 28f
}
