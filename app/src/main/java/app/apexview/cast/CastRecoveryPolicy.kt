package app.apexview.cast

/** Pure retry and handoff predicates. Never authenticate from network identity. */
internal object CastRecoveryPolicy {
    fun canRetry(remaining: Boolean, negotiated: Boolean, selected: Boolean, reason: String, pinned: Boolean): Boolean =
        remaining && negotiated && selected && pinned &&
            reason in setOf("PEER_EOF", "READ_SocketException", "WRITE_SocketException",
                "READ_SocketTimeoutException", "WRITE_SocketTimeoutException", "CONTROL_QUEUE_FULL")
    fun canHandOffGoogle(matches: Boolean, playable: Boolean, customLayout: Boolean, layoutConfirmed: Boolean): Boolean =
        matches && playable && (!customLayout || layoutConfirmed)
}
