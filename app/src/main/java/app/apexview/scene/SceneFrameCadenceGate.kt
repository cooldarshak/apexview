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
                    playbackSpeed: Float = 1f, thermalStatus: Int = Int.MAX_VALUE,
                    pipelineHealthy: Boolean = true): Boolean {
        // Match the submitted cadence, NOT the unthinned media clock.
        // The current FrameThinner keeps accelerated 50fps footage near 50 output
        // frames/s. Never defer decoder SurfaceTexture.updateTexImage().
        val outputFps = SceneFrameRatePolicy.composedOutputHint(
            sourceFps, playbackSpeed, displayHz, thermalStatus, pipelineHealthy)
        if ((outputFps !in 49f..51f && outputFps !in 74f..76f) || displayHz !in 90f..165f ||
            !displayHz.isFinite() || lastVideoVsyncNs <= 0L ||
            frameTimeNs <= lastVideoVsyncNs
        ) {
            consecutiveDeferrals = 0
            return false
        }
        // Use the shorter legitimate display interval for a fractional cadence.
        // 90/50 needs one- and two-vsync gaps; forcing two would cap output at 45fps.
        // 120/50 needs two- and three-vsync gaps. Never gate 75fps as though it were 50.
        val shortestSlots = (displayHz / outputFps + 0.005f).toInt()
        if (shortestSlots < 2) {
            consecutiveDeferrals = 0
            return false
        }
        val earliestNextFrameNs = (shortestSlots * 1_000_000_000.0 / displayHz).toLong() - 1_500_000L
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
