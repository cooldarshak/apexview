package app.apexview.scene

/** A rendering hint, never a reason to change decoder count or fail video playback. */
internal object SceneFrameRatePolicy {
    /** Zero removes a stale hint when the scene's media cadence is unknown. */
    fun hint(sourceFramesPerSecond: Double): Float =
        if (sourceFramesPerSecond.isFinite() && sourceFramesPerSecond in 1.0..240.0)
            sourceFramesPerSecond.toFloat() else 0f
}
