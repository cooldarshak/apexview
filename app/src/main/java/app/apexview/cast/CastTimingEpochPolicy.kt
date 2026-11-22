package app.apexview.cast

/**
 * First packet in a new receiver session may inherit a non-zero sender seek
 * epoch. Never accept a mismatched epoch after the first frame or after a
 * receiver-initiated seek; those packets are stale or from another timeline.
 */
internal object CastTimingEpochPolicy {
    fun adoptInitialEpoch(
        acceptedFrames: Long,
        lastSequence: Long,
        pendingSeek: Boolean,
        incomingEpoch: Long,
    ): Boolean = incomingEpoch >= 0L &&
        acceptedFrames == 0L && lastSequence < 0L && !pendingSeek
}
