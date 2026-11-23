package app.apexview.tiled

/** Bounded ciphertext output history with a two-segment startup floor for 1.5x. */
internal object ProtectedOutputHistoryPolicy {
    const val MAX_ENTRIES = 3
    const val MAX_RETAINED_BYTES = 160L * 1024L * 1024L
    const val MIN_STARTUP_ENTRIES = 2

    /**
     * Number of oldest fragments to drop. Only metadata sizes are read, never CBCS bytes.
     * At worst 2 verified outputs are kept even if an unusually large valid fragment
     * temporarily exceeds the soft aggregate limit. Media3 package generations
     * separately own their bytes until all reader leases close.
     */
    fun evictions(sizes: List<Int>): Int {
        require(sizes.all { it > 0 })
        var remaining = sizes.size
        var bytes = sizes.sumOf { it.toLong() }
        var removed = 0
        while (remaining > MIN_STARTUP_ENTRIES &&
            (remaining > MAX_ENTRIES || bytes > MAX_RETAINED_BYTES)) {
            bytes -= sizes[removed].toLong()
            removed++
            remaining--
        }
        return removed
    }
}
