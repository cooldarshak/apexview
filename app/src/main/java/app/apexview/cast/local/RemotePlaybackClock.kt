package app.apexview.cast.local

import kotlin.math.abs

/**
 * Phone-side approximation of the TV's authoritative Media3 clock. Source
 * packets still use the TV's own clock for rendering; this estimator only
 * timestamps the phone's next 200ms measured map positions.
 */
internal class RemotePlaybackClock(private val nowMs: () -> Long) {
    private var position = 0L
    private var stamp = 0L
    private var initialized = false
    private var running = false
    private var playbackSpeed = 1f
    private var projectedSpeed = 1f
    private var duration = Long.MAX_VALUE

    fun positionMs(): Long =
        (position + if (running) ((nowMs() - stamp).coerceAtLeast(0) * projectedSpeed).toLong() else 0L)
            .coerceIn(0L, duration)

    /** Intentional seek, pause, speed change, new session and first clock snapshot. */
    fun update(timeMs: Long, playing: Boolean, speed: Float, durationMs: Long = duration) {
        position = timeMs.coerceAtLeast(0L)
        stamp = nowMs()
        initialized = true
        running = playing
        playbackSpeed = validSpeed(speed)
        projectedSpeed = playbackSpeed
        duration = validDuration(durationMs)
    }

    /**
     * A normal one-second receiver heartbeat may lead/lag a few dozen ms.
     * Resetting the cursor to every jittery report generated backward steps
     * in the phone's map packet timestamps even while TV playback was smooth.
     *
     * Slew the projection rate, not the cursor: the estimated playhead stays
     * monotone during ordinary playback. Explicit seeks and state transitions
     * still hard reset; large corrections (>750ms) represent a changed timeline.
     */
    fun reconcile(timeMs: Long, playing: Boolean, speed: Float, durationMs: Long = duration) {
        val nextSpeed = validSpeed(speed)
        val target = timeMs.coerceAtLeast(0L)
        val nextDuration = validDuration(durationMs)
        if (!initialized || playing != running || abs(nextSpeed - playbackSpeed) > 0.001f) {
            update(target, playing, nextSpeed, nextDuration)
            return
        }
        val predicted = positionMs()
        val drift = target - predicted
        if (!playing || abs(drift) >= HARD_CORRECTION_MS) {
            update(target, playing, nextSpeed, nextDuration)
            return
        }
        position = predicted.coerceIn(0L, nextDuration)
        stamp = nowMs()
        duration = nextDuration
        playbackSpeed = nextSpeed
        // With 1Hz receiver reports, at most 60ms/s is corrected without a
        // backwards seek or a premature jump forward on the sender.
        val factor = 1f + (drift.toFloat() / SLEW_HORIZON_MS).coerceIn(-MAX_SLEW, MAX_SLEW)
        projectedSpeed = (nextSpeed * factor).coerceIn(0.25f, 4f)
    }

    private fun validSpeed(value: Float): Float =
        value.takeIf { it.isFinite() && it in 0.25f..4f } ?: 1f

    private fun validDuration(value: Long): Long = value.takeIf { it > 0L } ?: Long.MAX_VALUE

    private companion object {
        const val HARD_CORRECTION_MS = 750L
        const val SLEW_HORIZON_MS = 2_000f
        const val MAX_SLEW = 0.06f
    }
}
