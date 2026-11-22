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
}
