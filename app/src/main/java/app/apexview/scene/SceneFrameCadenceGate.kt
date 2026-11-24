package app.apexview.scene

/**
 * A bounded display-only pacing guard for 50 fps video on high-refresh displays.
 *
 * It never blocks SurfaceTexture.updateTexImage(): codec BufferQueue buffers must be
 * released immediately. It only defers an already-scheduled scene EGL draw until the
 * next Choreographer callback when that draw would follow the previous video draw
 * unnaturally soon. A two-vsync cap prevents starvation after stalls or seeks.
 * At 60 Hz (including TVs) the existing presentation path is unchanged.
 */
internal class SceneFrameCadenceGate {
    private var lastVideoVsyncNs = 0L
    private var consecutiveDeferrals = 0

    fun shouldDefer(frameTimeNs: Long, sourceFps: Double, displayHz: Float,
                    playbackSpeed: Float = 1f, thermalStatus: Int = Int.MAX_VALUE): Boolean {
        // Match the submitted cadence, NOT the unthinned media clock.
        // The current FrameThinner keeps accelerated 50fps footage near 50 output
        // frames/s. Never defer decoder SurfaceTexture.updateTexImage().
        val outputFps = SceneFrameRatePolicy.composedOutputHint(
            sourceFps, playbackSpeed, displayHz, thermalStatus)
        if (outputFps !in 49f..51f || displayHz !in 90f..165f ||
            !displayHz.isFinite() || lastVideoVsyncNs <= 0L ||
            frameTimeNs <= lastVideoVsyncNs
        ) {
            consecutiveDeferrals = 0
            return false
        }
        // A video frame every ~20ms should not result in an 8.33ms display burst.
        // The nearest 120Hz slots to a 50fps cadence are 16.67ms and 25ms apart.
        val earliestNextFrameNs = 15_000_000L
        if (frameTimeNs - lastVideoVsyncNs >= earliestNextFrameNs || consecutiveDeferrals >= 2) {
            consecutiveDeferrals = 0
            return false
        }
        consecutiveDeferrals++
        return true
    }

    fun onVideoDraw(frameTimeNs: Long) {
        lastVideoVsyncNs = frameTimeNs
        consecutiveDeferrals = 0
    }

    fun reset() {
        lastVideoVsyncNs = 0L
        consecutiveDeferrals = 0
    }
}
