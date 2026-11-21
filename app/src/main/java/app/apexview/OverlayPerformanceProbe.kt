package app.apexview

import android.util.Log
import kotlin.math.max

/** Five-second fixed-bin overlay counters. Never logs positions, IDs or credentials. */
internal class OverlayPerformanceProbe(
    private val owner: String,
    private val report: (String) -> Unit = { Log.i("PitwallOverlayPerf", it) },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val buckets = LongArray(9)
    private var startedMs = Long.MIN_VALUE
    private var samples = 0L
    private var entities = 0L
    private var changed = 0L
    private var seeks = 0L
    private var duration = 0L
    private var slowest = 0L

    fun record(durationNs: Long, count: Int, changedCount: Int, seekCount: Long = 0) {
        val now = clockMs()
        if (startedMs == Long.MIN_VALUE) startedMs = now
        samples++
        entities += count.coerceAtLeast(0)
        changed += changedCount.coerceAtLeast(0)
        seeks += seekCount.coerceAtLeast(0L)
        val bounded = durationNs.coerceAtLeast(0L)
        duration += bounded
        slowest = max(slowest, bounded)
        val micros = bounded / 1_000L
        val bin = when {
            micros < 250 -> 0
            micros < 500 -> 1
            micros < 1_000 -> 2
            micros < 2_000 -> 3
            micros < 4_000 -> 4
            micros < 8_000 -> 5
            micros < 16_000 -> 6
            micros < 32_000 -> 7
            else -> 8
        }
        buckets[bin]++
        if (now - startedMs >= 5_000L) emit(now)
    }

    private fun emit(now: Long) {
        if (samples == 0L) return
        val target = (samples * 95L + 99L) / 100L
        var through = 0L
        var bin = 0
        for (index in buckets.indices) {
            through += buckets[index]
            if (through >= target) { bin = index; break }
        }
        val upper = longArrayOf(250, 500, 1000, 2000, 4000, 8000, 16000, 32000, -1)[bin]
        report("OVERLAY_5S owner=$owner samples=$samples meanObjects=" + (entities / samples) +
            " changed=$changed seeks=$seeks meanUs=" + (duration / samples / 1_000L) +
            " p95BucketUpperUs=$upper maxUs=" + (slowest / 1_000L))
        buckets.fill(0)
        samples = 0; entities = 0; changed = 0; seeks = 0
        duration = 0; slowest = 0; startedMs = now
    }
}
