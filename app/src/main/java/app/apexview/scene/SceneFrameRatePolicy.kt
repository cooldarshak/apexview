package app.apexview.scene

import app.apexview.AdaptivePlaybackCadence

/** A rendering hint, never a reason to change decoder count or fail video playback. */
internal object SceneFrameRatePolicy {
    /** Zero removes a stale hint when the scene's media cadence is unknown. */
    fun hint(sourceFramesPerSecond: Double, playbackSpeed: Float = 1f): Float {
        val effective = sourceFramesPerSecond * playbackSpeed
        return if (playbackSpeed.isFinite() && playbackSpeed > 0f &&
            effective.isFinite() && effective in 1.0..240.0)
            effective.toFloat() else 0f
    }

    /**
     * Real composed output cadence for the current FrameThinner policy.
     * Accelerated 50fps media advances at 75fps at 1.5x but is deliberately
     * thinned to ~50 output frames/s. Keep the platform vote honest; do not
     * conflate the media clock with submitted pictures.
     */
    fun composedOutputHint(sourceFramesPerSecond: Double, playbackSpeed: Float = 1f,
                           displayHz: Float = 0f, thermalStatus: Int = Int.MAX_VALUE): Float =
        AdaptivePlaybackCadence.outputFps(sourceFramesPerSecond, playbackSpeed,
            displayHz, thermalStatus, composedSingle = true)

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

    /** Platform mode descriptors without Android dependencies, for exact JVM tests.
     * Only same-resolution modes can be considered seamless candidates. The OS
     * owns actual mode choice; callers never set a preferred mode ID. */
    data class Mode(val id: Int, val width: Int, val height: Int, val refreshHz: Float)

    fun matchingSeamlessModes(
        active: Mode, supported: List<Mode>,
        sourceFps: Double, playbackSpeed: Float
    ): List<Mode> {
        val videoFps = hint(sourceFps, playbackSpeed)
        if (active.width <= 0 || active.height <= 0 || videoFps == 0f) return emptyList()
        return supported.filter { mode ->
            mode.width == active.width && mode.height == active.height &&
                isIntegerRefreshMultiple(mode.refreshHz, sourceFps, playbackSpeed)
        }.distinctBy { it.id }.sortedWith(
            compareBy<Mode> { if (it.id == active.id) 0 else 1 }
                .thenBy { it.refreshHz }
                .thenBy { it.id }
        )
    }
}
