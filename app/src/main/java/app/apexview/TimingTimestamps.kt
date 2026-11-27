package app.apexview

import app.apexview.tiled.NativeHotPath

/** Immutable logical prefix. Completed native archives retain only the
 * compact Rust owner; live builders retain their append-only JVM prefix.
 * JVM reference reads use the same exact UTC values, without recreating arrays. */
internal class TimingTimestamps private constructor(
    val array: LongArray?,
    val native: NativeHotPath.CompactTimes?,
    val count: Int,
) {
    val handle: Long get() = native?.handle() ?: 0L
    operator fun get(index: Int): Long {
        require(index in 0 until count)
        return native?.at(index) ?: requireNotNull(array)[index]
    }
    // Existing diagnostic/test access only. Production cache and sampler
    // consumers read indexed values or pass the compact native handle.
    fun legacyArray(): LongArray = array ?: LongArray(count) { this[it] }
    companion object {
        fun jvm(array: LongArray, count: Int): TimingTimestamps {
            require(count in 0..array.size)
            return TimingTimestamps(array, null, count)
        }
        fun packed(source: LongArray, count: Int): TimingTimestamps? =
            NativeHotPath.compactTimes(source, count)?.let { TimingTimestamps(null, it, count) }
    }
}
