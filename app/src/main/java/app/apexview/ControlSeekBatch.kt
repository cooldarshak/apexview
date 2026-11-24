package app.apexview

/** Main-owner fixed-window coalescing. No preview decoder or approximate final seek. */
internal class ControlSeekBatch {
    companion object { const val WINDOW_MS = 80L }
    var targetMs: Long? = null
        private set
    /** True only for the first request; later requests replace it without extending the timer. */
    fun offer(target: Long): Boolean {
        val start = targetMs == null
        targetMs = target.coerceAtLeast(0L)
        return start
    }
    fun take(): Long? = targetMs.also { targetMs = null }
    fun clear() { targetMs = null }
}
