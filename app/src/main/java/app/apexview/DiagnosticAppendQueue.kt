package app.apexview

/** Bounded direct-producer mailbox; disk I/O happens only in the capture/flush owner. */
internal class DiagnosticAppendQueue(
    private val maxEntries: Int = 128,
    private val maxChars: Int = 256 * 1024,
) {
    init { require(maxEntries > 0 && maxChars > 0) }
    data class Drain(val lines: List<String>, val rejected: Long)
    private val lines = java.util.ArrayDeque<String>()
    private var chars = 0
    private var rejected = 0L
    private var generation: Int? = null

    @Synchronized fun start(token: Int) {
        lines.clear(); chars = 0; rejected = 0; generation = token
    }
    @Synchronized fun offer(text: String): Boolean {
        if (generation == null) return false
        val bounded = text.take(64 * 1024)
        if (lines.size >= maxEntries || bounded.length > maxChars - chars) {
            rejected++
            return false
        }
        lines.addLast(bounded); chars += bounded.length
        return true
    }
    @Synchronized fun drain(token: Int, close: Boolean = false): Drain {
        if (generation != token) return Drain(emptyList(), 0)
        val result = Drain(lines.toList(), rejected)
        lines.clear(); chars = 0; rejected = 0
        if (close) generation = null
        return result
    }
}
