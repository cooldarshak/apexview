package app.apexview

/** Recompute from the currently selected display mode, not the mode at window creation. */
internal object FrameDeadlinePolicy {
    fun durationNs(refreshHz: Float): Long {
        val hz = refreshHz.takeIf { it.isFinite() && it > 1f } ?: 60f
        return (1_000_000_000.0 / hz).toLong()
    }
}
