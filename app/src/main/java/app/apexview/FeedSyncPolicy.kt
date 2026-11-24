package app.apexview

/**
 * Pure clock-correction policy for explicitly MULTIPLE-decoder followers.
 * SINGLE composition never routes additional source video decoders through
 * this policy. Player ownership and seeks stay with AppState.
 */
internal object FeedSyncPolicy {
    private const val SEEK_DRIFT_MS = 2_000L
    private const val NUDGE_DRIFT_MS = 150L
    private const val NUDGE = 0.1f

    data class Correction(val speedMultiplier: Float, val seekPositionMs: Long? = null)

    fun decide(driftMs: Long, followerPositionMs: Long, playing: Boolean): Correction {
        if (driftMs > SEEK_DRIFT_MS || driftMs < -SEEK_DRIFT_MS) {
            // Guard bounds instead of computing abs(Long.MIN_VALUE) or
            // overflowing currentPosition-drift on a discontinuity.
            val target = when {
                driftMs > 0L && followerPositionMs <= driftMs -> 0L
                driftMs < 0L && followerPositionMs > Long.MAX_VALUE + driftMs -> Long.MAX_VALUE
                else -> (followerPositionMs - driftMs).coerceAtLeast(0L)
            }
            return Correction(1f, target)
        }
        if (playing) {
            if (driftMs > NUDGE_DRIFT_MS) return Correction(1f - NUDGE)
            if (driftMs < -NUDGE_DRIFT_MS) return Correction(1f + NUDGE)
        }
        return Correction(1f)
    }
}
