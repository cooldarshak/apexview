package app.apexview.cast

/** Pure retry and handoff predicates. Never authenticate from network identity. */
internal object CastRecoveryPolicy {
    fun controllerOnly(remoteHandedOff: Boolean, bakedProducer: Boolean, bakedReplacement: Boolean): Boolean =
        remoteHandedOff || bakedProducer || bakedReplacement

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
