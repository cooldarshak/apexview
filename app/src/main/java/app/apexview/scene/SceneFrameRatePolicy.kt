package app.apexview.scene

/** A rendering hint, never a reason to change decoder count or fail video playback. */
internal object SceneFrameRatePolicy {
    /** Zero removes a stale hint when the scene's media cadence is unknown. */
    fun hint(sourceFramesPerSecond: Double, playbackSpeed: Float = 1f): Float {
        val effective = sourceFramesPerSecond * playbackSpeed
        return if (playbackSpeed.isFinite() && playbackSpeed > 0f &&
            effective.isFinite() && effective in 1.0..240.0)
            effective.toFloat() else 0f
    }

    /** Integral display-to-video cadence is a diagnostic, NOT observed scanout.
     * The Surface.setFrameRate policy remains seamless-only. */
    fun isIntegerRefreshMultiple(
        refreshHz: Float, sourceFramesPerSecond: Double, playbackSpeed: Float = 1f
    ): Boolean {
        val videoHz = hint(sourceFramesPerSecond, playbackSpeed)
        if (!refreshHz.isFinite() || refreshHz <= 0f || videoHz <= 0f) return false
        val ratio = refreshHz.toDouble() / videoHz.toDouble()
        val nearest = kotlin.math.round(ratio)
        return nearest >= 1.0 && kotlin.math.abs(ratio - nearest) <= 0.005
    }
}
