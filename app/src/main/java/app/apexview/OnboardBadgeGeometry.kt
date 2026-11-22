package app.apexview

/** A feed-local anchor; no coordinates are ever measured against the whole phone/TV screen. */
enum class OnboardBadgeAnchor { TOP_LEFT, TOP_RIGHT }

/**
 * Pure geometry shared by the Android and TV overlays. Keeping this outside Compose allows
 * real boundary and layout-transition tests without mocking a video or a decoder.
 */
internal object OnboardBadgeGeometry {
    const val OFFSET_LIMIT_DP = 180f
    const val DEFAULT_OPACITY = 0.72f
    fun offset(value: Float): Float =
        if (value.isFinite()) value.coerceIn(-OFFSET_LIMIT_DP, OFFSET_LIMIT_DP) else 0f
    fun opacity(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0.20f, 1f) else DEFAULT_OPACITY

    fun position(
        feedWidthDp: Float, feedHeightDp: Float,
        badgeWidthDp: Float, badgeHeightDp: Float,
        anchor: OnboardBadgeAnchor, xOffsetDp: Float, yOffsetDp: Float,
        marginDp: Float = 4f,
    ): Pair<Float, Float> {
        require(feedWidthDp.isFinite() && feedHeightDp.isFinite() &&
            badgeWidthDp.isFinite() && badgeHeightDp.isFinite())
        val lastX = (feedWidthDp - badgeWidthDp).coerceAtLeast(0f)
        val lastY = (feedHeightDp - badgeHeightDp).coerceAtLeast(0f)
        val origin = if (anchor == OnboardBadgeAnchor.TOP_LEFT)
            marginDp.coerceIn(0f, lastX)
        else (lastX - marginDp).coerceAtLeast(0f)
        return (origin + offset(xOffsetDp)).coerceIn(0f, lastX) to
            (marginDp + offset(yOffsetDp)).coerceIn(0f, lastY)
    }
}
