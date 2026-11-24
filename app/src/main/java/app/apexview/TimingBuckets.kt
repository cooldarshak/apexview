package app.apexview

/** Fixed-size, allocation-free-on-record timing distribution for hot playback paths.
 * Values are nanoseconds; the bins deliberately separate 120/60/50 fps cadences.
 * These are callback and CPU durations, not SurfaceFlinger presentation times.
 */
internal class TimingBuckets {
    private val counts = LongArray(7)
    private var count = 0L
    private var totalNs = 0L
    private var maxNs = 0L

    fun record(ns: Long) {
        if (ns < 0L) return
        counts[when {
            ns < 13_000_000L -> 0
            ns < 19_000_000L -> 1
            ns < 23_000_000L -> 2
            ns < 30_000_000L -> 3
            ns < 45_000_000L -> 4
            ns < 100_000_000L -> 5
            else -> 6
        }]++
        count++
        totalNs += ns
        maxNs = maxOf(maxNs, ns)
    }

    fun snapshotAndReset(): Snapshot {
        val result = Snapshot(count, totalNs / 1_000_000.0, maxNs / 1_000_000.0,
            counts.copyOf())
        counts.fill(0L)
        count = 0L
        totalNs = 0L
        maxNs = 0L
        return result
    }

    internal data class Snapshot(val count: Long, val totalMs: Double, val maxMs: Double,
                                 val bins: LongArray) {
        val meanMs: Double get() = if (count == 0L) 0.0 else totalMs / count
        /** Histogram bounds; percentile values are upper bounds, not exact samples. */
        fun percentileUpperMs(percentile: Int): Int {
            if (count == 0L) return 0
            val target = ((count * percentile + 99) / 100).coerceAtLeast(1)
            var seen = 0L
            for (i in bins.indices) {
                seen += bins[i]
                if (seen >= target) return if (i == bins.lastIndex) kotlin.math.ceil(maxMs).toInt()
                    else intArrayOf(13, 19, 23, 30, 45, 100)[i]
            }
            return kotlin.math.ceil(maxMs).toInt()
        }

        /** Ordered bins: <13, 13-19, 19-23, 23-30, 30-45, 45-100, >=100 ms. */
        fun compact(): String = bins.joinToString(",")
    }
}

internal object DisplayCadence {
    fun exactMultiple(displayHz: Float, videoFps: Double): Boolean {
        if (displayHz <= 0f || videoFps <= 0.0) return false
        val ratio = displayHz.toDouble() / videoFps
        return kotlin.math.abs(ratio - kotlin.math.round(ratio)) < 0.02
    }
}
