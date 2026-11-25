package app.apexview.tiled

/** Process-wide reservation for overlapping speculative archive groups. A reservation
 * is a forecast, not resident bytes. Never reject or throttle foreground media. */
internal class SpeculativeMemoryAdmission {
    private var reserved = 0L
    private var peak = 0L
    @Synchronized fun tryReserve(charge: Long, limit: Long): AutoCloseable? {
        if (charge <= 0 || limit < reserved || charge > limit - reserved) return null
        reserved += charge
        peak = maxOf(peak, reserved)
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) synchronized(this) {
                check(reserved >= charge)
                reserved -= charge
            }
        }
    }
    @Synchronized fun snapshot() = reserved to peak
    companion object {
        val process = SpeculativeMemoryAdmission()
        fun charge(input: Long, output: Long): Long? =
            if (input in 1..Int.MAX_VALUE.toLong() && output in 1..Int.MAX_VALUE.toLong())
                input + 2L * output + 8L * 1024 * 1024 else null
        fun limit(h: NativeStitchingDeviceBudget.Headroom): Long =
            if (h.lowMemory || h.threshold < 0 || h.available <= h.threshold) 0L
            else minOf((h.available - h.threshold) / 8, h.jvmHeadroom / 2).coerceAtLeast(0L)
    }
}
