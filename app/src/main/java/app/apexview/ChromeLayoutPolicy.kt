package app.apexview

/** Adaptive Chrome budget for Material 3 controls at phone and TV font scales. */
internal object ChromeLayoutPolicy {
    fun compact(widthDp: Float, fontScale: Float): Boolean {
        if (!widthDp.isFinite() || widthDp <= 0f) return true
        val scale = if (fontScale.isFinite()) fontScale.coerceAtLeast(1f) else 1f
        // Large text requires a second toolbar row earlier, even on tablets.
        // TV reports screen dimensions in dp, not physical 4K pixels. At 200%
        // font scale a 960dp TV must not squeeze twelve focusable controls and
        // the player title onto one row: split overflow actions to a scroll row.
        return widthDp < 540f || (scale >= 1.5f && widthDp < 1200f)
    }
}
