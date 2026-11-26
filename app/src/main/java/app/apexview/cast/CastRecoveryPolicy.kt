package app.apexview.cast

/** Pure retry and handoff predicates. Never authenticate from network identity. */
internal object CastRecoveryPolicy {
    fun canRetry(remaining: Boolean, negotiated: Boolean, selected: Boolean, reason: String, pinned: Boolean): Boolean =
        remaining && negotiated && selected && pinned &&
            reason in setOf("PEER_EOF", "READ_SocketException", "WRITE_SocketException",
                "READ_SocketTimeoutException", "WRITE_SocketTimeoutException", "CONTROL_QUEUE_FULL")
    fun canHandOffGoogle(matches: Boolean, playable: Boolean, customLayout: Boolean, layoutConfirmed: Boolean): Boolean =
        matches && playable && (!customLayout || layoutConfirmed)
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
