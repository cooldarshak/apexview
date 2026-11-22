package app.apexview.tiled

/**
 * Low-overhead diagnostic heartbeat for rolling capture. Never suppress
 * real rendition/policy changes: changed content is immediately reportable.
 * The monotonic clock bounds identical snapshots to one per interval.
 */
internal class BoundedChangeLog(private val intervalMs: Long = 5_000L) {
    private var previous = ""
    private var lastMs = Long.MIN_VALUE

    init { require(intervalMs > 0L) }

    @Synchronized fun shouldLog(fingerprint: String, nowMs: Long): Boolean {
        val changed = fingerprint != previous
        val due = lastMs == Long.MIN_VALUE || nowMs < lastMs ||
            nowMs - lastMs >= intervalMs
        if (!changed && !due) return false
        previous = fingerprint
        lastMs = nowMs
        return true
    }
}
