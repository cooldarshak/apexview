package app.apexview.tiled

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Only one producer owns a numbered segment at a time. Unrelated segments never share a
 * production lock. The future signals publication into the caller's separately owned cache;
 * it deliberately does not retain media bytes or outlive its last interested caller.
 */
internal class ArchiveSegmentSingleFlight(private val onRegistered: ((Int) -> Unit)? = null) {
    private class Flight(val finished: CompletableFuture<Unit> = CompletableFuture(), var users: Int = 0)
    private val lock = Any()
    private val flights = mutableMapOf<Int, Flight>()

    fun run(number: Int, produce: () -> Unit) {
        val (flight, producer) = synchronized(lock) {
            val existing = flights[number]
            val current = existing ?: Flight().also { flights[number] = it }
            current.users++
            current to (existing == null)
        }
        try {
            onRegistered?.invoke(number)
            if (producer) {
                try {
                    produce()
                    flight.finished.complete(Unit)
                } catch (failure: Throwable) {
                    flight.finished.completeExceptionally(failure)
                }
            }
            try {
                flight.finished.get()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (failed: ExecutionException) {
                throw requireNotNull(failed.cause)
            }
        } finally {
            synchronized(lock) {
                flight.users--
                if (flight.users == 0) flights.remove(number, flight)
            }
        }
    }
}

/** Immutable metadata only; no sample copies are needed to inspect a composed buffer. */
internal data class CmafWindowTiming(val startUs: Long, val endUs: Long) {
    fun shifted(offsetUs: Long) = CmafWindowTiming(Math.addExact(startUs, offsetUs), Math.addExact(endUs, offsetUs))

    companion object {
        fun ticksToUs(ticks: Long, timescale: Long): Long = java.math.BigInteger.valueOf(ticks)
            .multiply(java.math.BigInteger.valueOf(1_000_000)).divide(java.math.BigInteger.valueOf(timescale)).longValueExact()

        fun from(fragments: List<ProtectedHevcLiveComposition.OutputFragment>): CmafWindowTiming? {
            val first = fragments.firstOrNull() ?: return null
            val last = fragments.last()
            return CmafWindowTiming(ticksToUs(first.baseDecodeTimeTicks, first.timescale),
                ticksToUs(Math.addExact(last.baseDecodeTimeTicks, last.durationTicks), last.timescale))
        }
    }
}

internal object ArchiveBufferPolicy {
    const val RETAINED_SEGMENTS = 3
    const val STARTUP_LEAD_US = 3_000_000L

    /**
     * Rolling CBCS archive composition is expensive, especially a four-source
     * 3840x2176 picture. Starting the composed player as soon as a single
     * almost-expired segment exists caused ~130ms player buffer and repeated
     * rebuffering on the S22 Ultra. Preserve two 3-second equivalents at 1x,
     * scaled to media time at faster playback. Never reduce the user's
     * chosen source quality or switch to multiple video decoders.
     */
    fun protectedLeadUs(playbackSpeed: Float): Long {
        val speed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
        return (STARTUP_LEAD_US * 2.0 * maxOf(1.0, speed.toDouble())).toLong()
    }

    /** A finite, cache-sized warm window. Never prefetch beyond the advertised archive. */
    fun warmFollowing(number: Int, lastIndex: Int): IntRange {
        require(number >= 0 && lastIndex >= number)
        return (number + 1)..minOf(lastIndex, number + RETAINED_SEGMENTS - 1)
    }

    /**
     * Uncached playback outranks an unrelated warmup, while normal cache hits leave it
     * running. An explicit seek also cancels stale work even if the target is cached.
     * Work already composing the requested segment is shared instead of cancelled.
     */
    fun preemptWarmup(requested: Int, cached: Boolean, active: Int?, seek: Boolean = false): Boolean =
        (seek || !cached) && active != requested

    /** Keep the segment containing the audio clock plus two following segments. */
    fun maximumLeadUs(candidates: List<ArchiveDashSegmentSelector.Candidate>, positionUs: Long): Long {
        if (candidates.isEmpty()) return 0
        val current = candidates.indexOfLast { it.absoluteStartUs <= positionUs }.coerceAtLeast(0)
        val limit = candidates[minOf(candidates.lastIndex, current + RETAINED_SEGMENTS - 1)].absoluteStartUs
        return (limit - positionUs).coerceAtLeast(0)
    }

    /** A validated but already expired first fragment must never take over the decoder. */
    fun ready(timing: CmafWindowTiming?, clockUs: Long, durationUs: Long?, minimumLeadUs: Long = STARTUP_LEAD_US): Boolean {
        if (timing == null || timing.startUs > clockUs || timing.endUs <= clockUs) return false
        val remaining = durationUs?.let { (it - clockUs).coerceAtLeast(0) }
        val required = minOf(minimumLeadUs, remaining ?: minimumLeadUs)
        return timing.endUs - clockUs >= required
    }

    fun audioMayPlay(userPlaying: Boolean, videoReady: Boolean, fillingStartupBuffer: Boolean = false) =
        userPlaying && videoReady && !fillingStartupBuffer
}
