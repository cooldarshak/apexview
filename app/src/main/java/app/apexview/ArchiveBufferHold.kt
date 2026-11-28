package app.apexview

/** A soft load admission gate, never an allocator cap or a sample eviction.
 * Only valid after the measured selected lanes and upstream have qualified.
 * The conservative time low-watermark always takes precedence over bytes. */
internal class ArchiveBufferHold {
    private var held = false
    private var generation = Long.MIN_VALUE
    fun hold(target: ArchiveForwardBufferPolicy.Target?, bufferedUs: Long, allocatedBytes: Long): Boolean {
        if (target == null) {
            generation = Long.MIN_VALUE
            held = false
            return false
        }
        if (generation != target.generation) {
            generation = target.generation
            held = false
        }
        if (bufferedUs < target.lowUs) held = false
        else if (bufferedUs >= target.highUs || allocatedBytes >= 55L * 1024 * 1024) held = true
        return held
    }
}
