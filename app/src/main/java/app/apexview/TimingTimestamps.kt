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

/** Exact coordinates. Completed archives may use a single native packed owner;
 * live prefixes and any non-representable archive remain JVM float arrays. */
internal class TimingCoordinates private constructor(
    val xArray: FloatArray?,
    val yArray: FloatArray?,
    val native: NativeHotPath.CompactCoordinates?,
    val count: Int,
) {
    val handle: Long get() = native?.handle() ?: 0L
    fun xAt(index: Int): Float = native?.xAt(index) ?: requireNotNull(xArray)[index]
    fun yAt(index: Int): Float = native?.yAt(index) ?: requireNotNull(yArray)[index]
    fun legacyX(): FloatArray = xArray ?: FloatArray(count) { xAt(it) }
    fun legacyY(): FloatArray = yArray ?: FloatArray(count) { yAt(it) }
    companion object {
        fun jvm(x: FloatArray, y: FloatArray, count: Int): TimingCoordinates {
            require(count in 0..minOf(x.size, y.size))
            return TimingCoordinates(x, y, null, count)
        }
        fun packed(x: FloatArray, y: FloatArray, count: Int): TimingCoordinates? =
            NativeHotPath.compactCoordinates(x, y, count)?.let { TimingCoordinates(null, null, it, count) }
    }
}

/** Exact CarData values; keep live prefixes and unsupported archive inputs
 * in the original shorts, never keep both arrays and a native owner. */
internal class TimingTelemetry private constructor(
    val array: ShortArray?,
    val native: NativeHotPath.CompactTelemetry?,
    val count: Int,
) {
    operator fun get(index: Int): Int {
        require(index in 0 until count)
        return native?.at(index) ?: requireNotNull(array)[index].toInt()
    }
    companion object {
        fun jvm(source: ShortArray, count: Int): TimingTelemetry {
            require(count in 0..source.size)
            return TimingTelemetry(source, null, count)
        }
        fun packed(source: ShortArray, count: Int, bits: Int): TimingTelemetry? =
            NativeHotPath.compactTelemetry(source, count, bits)?.let { TimingTelemetry(null, it, count) }
    }
}
