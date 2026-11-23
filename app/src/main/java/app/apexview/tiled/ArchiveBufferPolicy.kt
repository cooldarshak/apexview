package app.apexview.tiled

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.io.IOException

/**
 * Only one producer owns a numbered segment at a time. Unrelated segments never share a
 * production lock. The future signals publication into the caller's separately owned cache;
 * it deliberately does not retain media bytes or outlive its last interested caller.
 */
internal class ArchiveSegmentSingleFlight(private val onRegistered: ((Int) -> Unit)? = null) {
    private class Flight(val finished: CompletableFuture<Unit> = CompletableFuture(), var users: Int = 0)
    private val lock = Any()
    private val flights = mutableMapOf<Int, Flight>()
    private var closed = false

    /**
     * Closing the stream must wake every foreground joiner immediately,
     * even when a speculative producer is blocked in network I/O.
     * Producers are still responsible for cooperative cancellation; the
     * owning session's closed flag prevents any later cache publication.
     */
    fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            val abandoned = flights.values.toList()
            flights.clear()
            abandoned
        }
        pending.forEach { it.finished.completeExceptionally(IOException("Archive session closed")) }
    }

    fun run(number: Int, produce: () -> Unit) {
        val (flight, producer) = synchronized(lock) {
            if (closed) throw IOException("Archive session closed")
            val existing = flights[number]
            val current = existing ?: Flight().also { flights[number] = it }
            current.users++
            current to (existing == null)
        }
        try {
            // A registration callback is observational, but it may still throw.
            // If the first caller fails here, every concurrent joiner must be
            // released rather than waiting forever on an uncompleted future.
            try {
                onRegistered?.invoke(number)
            } catch (failure: Throwable) {
                if (producer) {
                    synchronized(lock) { flights.remove(number, flight) }
                    flight.finished.completeExceptionally(failure)
                }
                throw failure
            }
            if (producer) {
                try {
                    produce()
                    flight.finished.complete(Unit)
                } catch (failure: Throwable) {
                    // Do not leave a failed/cancelled producer registered while other waiters
                    // unwind. A new foreground request can take ownership immediately.
                    synchronized(lock) { flights.remove(number, flight) }
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

/**
 * Source init snapshots have explicit ownership independent from session-owned originals.
 * Construction zeros partial copies if a later copy fails; close zeros all snapshots even
 * when a seek or validation error occurs before composition starts.
 */
internal class OwnedArchiveInitializations private constructor(val copies: List<ByteArray>) : AutoCloseable {
    companion object {
        fun snapshot(sources: List<ByteArray>): OwnedArchiveInitializations {
            val copied = ArrayList<ByteArray>(sources.size)
            try {
                sources.forEach { copied += it.copyOf() }
                return OwnedArchiveInitializations(copied)
            } catch (failure: Throwable) {
                copied.forEach { it.fill(0) }
                throw failure
            }
        }
    }
    override fun close() { copies.forEach { it.fill(0) } }
}

/** Transient only: an interrupted prefetch must not fail an unrelated foreground seek. */
internal class ArchiveWarmupCancelledException : IOException("Archive prefetch cancelled")

/**
 * Owns each CMAF fragment exactly once. Callers keep their returned copies; evicted and
 * replaced cached ciphertext is zeroed. The session's cacheLock serializes operations.
 * Retaining three *items* alone is not enough when an 8-feed atlas grows to many MiB.
 */
internal class BoundedArchiveFragmentCache(
    private val maxEntries: Int = ArchiveBufferPolicy.RETAINED_SEGMENTS,
    private val maxBytes: Long = ArchiveBufferPolicy.MAX_RETAINED_MEDIA_BYTES,
) {
    init { require(maxEntries > 0 && maxBytes > 0) }
    private val entries = LinkedHashMap<Int, ByteArray>(maxEntries + 1, 0.75f, true)
    var bytesRetained: Long = 0L
        private set
    val size: Int get() = entries.size
    fun contains(number: Int): Boolean = entries.containsKey(number)
    fun readCopy(number: Int): ByteArray? = entries[number]?.copyOf()

    /** Takes ownership only on success; no request can observe a mutable cache-owned array. */
    fun put(number: Int, owned: ByteArray) {
        require(owned.isNotEmpty() && owned.size.toLong() <= maxBytes) {
            "Archive CMAF fragment exceeds the bounded cache budget"
        }
        // Protect against one ByteArray being inserted under two separate keys.
        require(entries.values.none { it === owned }) { "Archive fragment ownership already transferred" }
        entries.put(number, owned)?.let { previous ->
            bytesRetained -= previous.size
            if (previous !== owned) previous.fill(0)
        }
        bytesRetained += owned.size
        while (entries.size > maxEntries || bytesRetained > maxBytes) {
            val eldest = entries.entries.iterator()
            if (!eldest.hasNext()) break
            val victim = eldest.next()
            eldest.remove()
            bytesRetained -= victim.value.size
            victim.value.fill(0)
        }
    }

    fun clear() {
        entries.values.forEach { it.fill(0) }
        entries.clear()
        bytesRetained = 0L
    }
}

/** One deadline for the *entire* feed group, never 25 seconds per queued source. */
internal object ArchiveFetchDeadline {
    const val GROUP_TIMEOUT_MS = 25_000L

    /** Monotonic elapsed-budget arithmetic survives the usual nanoTime signed origin. */
    fun remainingNs(startedNs: Long, nowNs: Long, timeoutMs: Long = GROUP_TIMEOUT_MS): Long {
        require(timeoutMs > 0L && timeoutMs <= 60_000L)
        val budgetNs = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        return (budgetNs - (nowNs - startedNs)).coerceAtLeast(0L)
    }
}

internal object ArchiveBufferPolicy {
    // Bounded across feeds and composed atlas sizes, independent of fragment count.
    const val MAX_RETAINED_MEDIA_BYTES = 96L * 1024L * 1024L
    const val RETAINED_SEGMENTS = 3
    const val STARTUP_LEAD_US = 3_000_000L

    /**
     * A seek during a still-running archive capture invalidates its old cursor.
     * The source video clocks are held during preflight; avoid waiting 30s for
     * old fragments to advance to a completely different replay position.
     */
    fun preflightClockMoved(beforeMs: Long, afterMs: Long): Boolean =
        afterMs - beforeMs > 2_000L || beforeMs - afterMs > 2_000L

    fun restartPreflightOnSeek(
        starting: Boolean, captureActive: Boolean, hasComposedHost: Boolean,
        isSingleDecoder: Boolean,
    ): Boolean = starting && captureActive && !hasComposedHost && isSingleDecoder

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
