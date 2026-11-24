package app.apexview.tiled

/** Bounded ciphertext output history with a two-segment startup floor for 1.5x. */
internal object ProtectedOutputHistoryPolicy {
    const val MAX_ENTRIES = 3
    const val MAX_RETAINED_BYTES = 160L * 1024L * 1024L
    const val MIN_STARTUP_ENTRIES = 2

    /**
     * Number of oldest fragments to drop. Only metadata sizes are read, never CBCS bytes.
     * At least two verified outputs survive for 1.5x playback startup, but
     * **never** at the cost of exceeding the hard retained-byte limit. An
     * unsupported 7/8-feed bitrate must fail with a precise error instead of
     * allocating unbounded protected CMAF history. Media3 package generations
     * separately own their bytes until reader leases close.
     */
    fun evictions(sizes: List<Int>): Int {
        require(sizes.all { it > 0 }) { "Protected CMAF history has an empty fragment" }
        val minimumKept = minOf(MIN_STARTUP_ENTRIES, sizes.size)
        val essentialBytes = sizes.takeLast(minimumKept).sumOf { it.toLong() }
        require(essentialBytes <= MAX_RETAINED_BYTES) {
            "Protected CMAF minimum startup history ${essentialBytes} bytes exceeds the hard ${MAX_RETAINED_BYTES}-byte budget"
        }
        var remaining = sizes.size
        var bytes = sizes.sumOf { it.toLong() }
        var removed = 0
        while (remaining > minimumKept &&
            (remaining > MAX_ENTRIES || bytes > MAX_RETAINED_BYTES)) {
            bytes -= sizes[removed].toLong()
            removed++
            remaining--
        }
        check(bytes <= MAX_RETAINED_BYTES) { "Protected history exceeded hard byte budget" }
        return removed
    }
}
