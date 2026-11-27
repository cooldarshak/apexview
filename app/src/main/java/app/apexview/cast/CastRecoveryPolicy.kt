package app.apexview.cast

/** Pure retry and handoff predicates. Never authenticate from network identity. */
internal object CastRecoveryPolicy {
    /** Use the confirmed receiver clock, without a route-specific assertion. */
    fun handoffClock(positionMs: Long?, durationMs: Long?, fallbackPositionMs: Long, fallbackDurationMs: Long): Pair<Long, Long> =
        (positionMs?.takeIf { it >= 0 } ?: fallbackPositionMs.coerceAtLeast(0)) to
            (durationMs?.takeIf { it > 0 } ?: fallbackDurationMs)

    fun retireReplayQueues(remote: Boolean, parked: Boolean, items: Int, idle: Boolean,
                           emptyTimeline: Boolean, live: Boolean, seekable: Boolean): Boolean =
        remote && !parked && items > 0 && !idle && !emptyTimeline && !live && seekable

    /** null means use the player's default live-window start, not archive zero. */
    fun receiverStartPosition(baked: Boolean, requestedMs: Long, originMs: Long): Long? =
        if (baked) null else (requestedMs - originMs).coerceAtLeast(0L)

    fun controllerOnly(remoteHandedOff: Boolean, bakedProducer: Boolean, bakedReplacement: Boolean): Boolean =
        remoteHandedOff || bakedProducer || bakedReplacement

    /** Loading and rolling HLS may legitimately report zero duration. Reject invalid
     * status before committing its clock epoch or applying any media state. */
    fun validPlaybackStatus(state: Int, time: Double, duration: Double, speed: Double): Boolean =
        state in 0..2 && time.isFinite() && duration.isFinite() && speed.isFinite() &&
            time in 0.0..604800.0 && duration in 0.0..604800.0 && speed in 0.25..4.0

    fun canRetry(remaining: Boolean, negotiated: Boolean, selected: Boolean, reason: String, pinned: Boolean): Boolean =
        remaining && negotiated && selected && pinned &&
            reason in setOf("PEER_EOF", "READ_SocketException", "WRITE_SocketException",
                "READ_SocketTimeoutException", "WRITE_SocketTimeoutException", "CONTROL_QUEUE_FULL")
    fun canHandOffGoogle(matches: Boolean, playable: Boolean, customLayout: Boolean, layoutConfirmed: Boolean): Boolean =
        matches && playable && (!customLayout || layoutConfirmed)
    /** CAF media status and the layout receiver's rendered ACK arrive on
     * independent channels. Retain the current load's ACK even if CAF has
     * not delivered its matching status yet. Neither alone hands off. */
    fun matchesRenderingAck(expectedId: String, acknowledgedId: String?): Boolean =
        expectedId.isNotBlank() && acknowledgedId == expectedId

    /** The Android Cast API exposes the loaded content and custom data, not
     * the wire-level mediaSessionId. The relay URL is unique to its owner.
     * Standard receivers may omit custom data; never accept an explicit mismatch. */
    fun matchesGoogleLoad(expectedUrl: String?, expectedId: String, contentId: String?, echoedId: String?): Boolean =
        expectedUrl != null && expectedId.isNotBlank() && contentId == expectedUrl &&
            (echoedId.isNullOrBlank() || echoedId == expectedId)

    fun localLoadTimedOut(expectedId: String, actualId: String, sameConnection: Boolean,
                          selected: Boolean, active: Boolean, confirmed: Boolean): Boolean =
        expectedId.isNotBlank() && expectedId == actualId && sameConnection && selected && active && !confirmed
}

/** Coalesces controls issued before CAF adopts the requested media. A command
 * belongs to one load only and must not operate on an earlier/foreign stream. */
internal class GooglePendingLoadControls {
    data class Commands(val playing: Boolean?, val seekMs: Long?)
    private var owner = ""
    private var playing: Boolean? = null
    private var seekMs: Long? = null

    fun begin(loadId: String) {
        owner = loadId
        playing = null
        seekMs = null
    }

    fun play(loadId: String, value: Boolean) {
        if (owner.isNotEmpty() && owner == loadId) playing = value
    }

    fun seek(loadId: String, value: Long) {
        if (owner.isNotEmpty() && owner == loadId) seekMs = value.coerceAtLeast(0)
    }

    fun take(loadId: String, matches: Boolean): Commands? {
        if (!matches || owner.isEmpty() || owner != loadId || (playing == null && seekMs == null)) return null
        return Commands(playing, seekMs).also { playing = null; seekMs = null }
    }
}
