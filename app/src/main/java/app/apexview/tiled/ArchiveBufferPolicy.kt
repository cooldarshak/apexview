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
 * A read-only, single-owner handle. The backing array is never exposed to callers.
 * A cache eviction cannot erase a fragment until the last reader has closed.
 */
internal class ArchiveFragmentLease(
    private val bytes: ByteArray?,
    private val native: NativeFragmentLease?,
    private val release: () -> Unit,
) : AutoCloseable {
    constructor(bytes: ByteArray, release: () -> Unit) : this(bytes, null, release)
    init { require((bytes == null) != (native == null)) }
    private var closed = false
    val size: Int = bytes?.size ?: requireNotNull(native).size

    @Synchronized fun readInto(target: ByteArray, targetOffset: Int, sourceOffset: Int, length: Int) {
        check(!closed) { "Archive fragment lease is closed" }
        require(length >= 0 && targetOffset >= 0 && sourceOffset >= 0 &&
            targetOffset <= target.size - length && sourceOffset <= size - length)
        if (bytes != null) bytes.copyInto(target, targetOffset, sourceOffset, sourceOffset + length)
        else requireNotNull(native).readTo(target, targetOffset, sourceOffset, length)
    }

    @Synchronized fun writeTo(output: java.io.OutputStream, sourceOffset: Int, length: Int) {
        check(!closed) { "Archive fragment lease is closed" }
        require(length >= 0 && sourceOffset >= 0 && sourceOffset <= size - length)
        if (bytes != null) output.write(bytes, sourceOffset, length)
        else requireNotNull(native).writeTo(output, sourceOffset, length)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        release()
    }

    companion object {
        /** Small standalone resources (manifest/init) retain their existing zero-on-close contract. */
        fun owned(bytes: ByteArray) = ArchiveFragmentLease(bytes) { bytes.fill(0) }
    }
}

/**
 * Byte-bounded LRU with read leases for the clear Media3 archive loader.
 * Cache and pinned-reader budgets are independently bounded by maxBytes; at
 * most 2*maxBytes of unique backing data can be retained, including evictions.
 * The legacy copy API remains for callers that transfer mutable byte ownership.
 */
internal class BoundedArchiveFragmentCache(
    private val maxEntries: Int = ArchiveBufferPolicy.RETAINED_SEGMENTS,
    private val maxBytes: Long = ArchiveBufferPolicy.MAX_RETAINED_MEDIA_BYTES,
    private val maxReaders: Int = 8,
) {
    init { require(maxEntries > 0 && maxBytes > 0 && maxReaders > 0) }
    private class Entry(val bytes: ByteArray?, val native: NativeFragmentLease?) {
        init { require((bytes == null) != (native == null)) }
        val size get() = bytes?.size ?: requireNotNull(native).size
        val identity: Any get() = bytes ?: requireNotNull(native).storageIdentity
        fun dispose() { bytes?.fill(0); native?.close() }
        fun copyBytes() = bytes?.copyOf() ?: requireNotNull(native).copyBytes()
        var cached = true
        var readers = 0
    }
    private val entries = LinkedHashMap<Int, Entry>(maxEntries + 1, 0.75f, true)
    private val pinned = mutableSetOf<Entry>()
    private var readers = 0
    @get:Synchronized var bytesRetained: Long = 0L
        private set
    @get:Synchronized var leasedBytes: Long = 0L
        private set
    val size: Int @Synchronized get() = entries.size
    data class MemorySnapshot(val cached: Long, val pinned: Long, val retiredPinned: Long) {
        val unique: Long get() = Math.addExact(cached, retiredPinned)
    }
    @Synchronized fun memorySnapshot() = MemorySnapshot(bytesRetained, leasedBytes,
        pinned.filterNot { it.cached }.sumOf { it.size.toLong() })
    private fun recordMemory() {
        // Native fragment backing is already charged in the Rust arena.
        var owned = 0L
        var leased = 0L
        for (entry in entries.values) if (entry.bytes != null) {
            owned += entry.size
            if (entry.readers > 0) leased += entry.size
        }
        for (entry in pinned) if (!entry.cached && entry.bytes != null) {
            owned += entry.size; leased += entry.size
        }
        app.apexview.MediaMemoryAccounting.process.record(this,
            app.apexview.MediaMemoryAccounting.Kind.ARCHIVE_CACHE, owned, leased)
    }
    /** Foreground keeps its original admission and retry contract. Do not
     * start speculative production while retired readers consume the cache
     * allowance; current-window data may still be evicted normally. */
    @Synchronized fun canSpeculate(number: Int, outputEstimate: Long): Boolean {
        if (entries.containsKey(number)) return true
        val snapshot = memorySnapshot()
        return snapshot.retiredPinned == 0L || (outputEstimate > 0 &&
            outputEstimate <= maxBytes - snapshot.unique)
    }
    @Synchronized fun trimBehind(number: Int): Long {
        var freed = 0L
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key >= number || entry.value.readers != 0) continue
            freed += entry.value.size
            iterator.remove()
            retire(entry.value)
        }
        recordMemory()
        return freed
    }
    @Synchronized fun contains(number: Int): Boolean = entries.containsKey(number)
    @Synchronized fun readCopy(number: Int): ByteArray? = entries[number]?.copyBytes()

    @Synchronized fun readLease(number: Int): ArchiveFragmentLease? {
        val entry = entries[number] ?: return null
        if (readers >= maxReaders || (entry.readers == 0 &&
                entry.size.toLong() > maxBytes - leasedBytes)) {
            throw ArchiveVodMediaException("Archive read lease budget exhausted", retryable = true)
        }
        if (entry.readers == 0) {
            pinned.add(entry)
            leasedBytes += entry.size
        }
        entry.readers++
        readers++
        recordMemory()
        return ArchiveFragmentLease(entry.bytes, entry.native) { release(entry) }
    }

    @Synchronized private fun release(entry: Entry) {
        check(entry.readers > 0)
        entry.readers--
        readers--
        if (entry.readers == 0) {
            pinned.remove(entry)
            leasedBytes -= entry.size
            if (!entry.cached) entry.dispose()
        }
        recordMemory()
    }

    private fun retire(entry: Entry) {
        entry.cached = false
        bytesRetained -= entry.size
        if (entry.readers == 0) entry.dispose()
    }

    /** Transfers ownership only on success. Readers see stable bytes even after eviction. */
    @Synchronized fun put(number: Int, owned: ByteArray) = putEntry(number, Entry(owned, null))
    @Synchronized fun putNative(number: Int, owned: NativeFragmentLease) = putEntry(number, Entry(null, owned))

    private fun putEntry(number: Int, entry: Entry) {
        require(entry.size > 0 && entry.size.toLong() <= maxBytes) {
            "Archive CMAF fragment exceeds the bounded cache budget"
        }
        require(entries.values.none { it.identity === entry.identity } && pinned.none { it.identity === entry.identity }) {
            "Archive fragment ownership already transferred"
        }
        entries.put(number, entry)?.let(::retire)
        bytesRetained += entry.size
        while (entries.size > maxEntries || bytesRetained > maxBytes) {
            val eldest = entries.entries.iterator()
            val victim = eldest.next()
            eldest.remove()
            retire(victim.value)
        }
        recordMemory()
    }

    @Synchronized fun clear() {
        entries.values.forEach(::retire)
        entries.clear()
        check(bytesRetained == 0L)
        recordMemory()
    }
}

/**
 * Close-aware, bounded admission for an entire fetch + compose + publication group.
 * Separate instances own foreground and speculative work. A retired session wakes
 * queued producers immediately; active producers retain their normal cancellation
 * and ownership checks. No network or composition work executes under this lock.
 */
internal class ArchiveWorkAdmission(private val maxWaiters: Int) : AutoCloseable {
    init { require(maxWaiters >= 0) }
    private val lock = java.util.concurrent.locks.ReentrantLock(true)
    private val changed = lock.newCondition()
    private var busy = false
    private var waiting = 0
    private var closed = false

    fun queued(): Int {
        lock.lock()
        try { return waiting } finally { lock.unlock() }
    }

    fun <T> run(timeoutNs: Long, work: () -> T): T {
        require(timeoutNs >= 0)
        lock.lockInterruptibly()
        try {
            if (closed) throw IOException("Archive work admission closed")
            if (busy || waiting > 0) {
                if (waiting >= maxWaiters) throw ArchiveVodMediaException(
                    "Archive work admission queue full", retryable = true)
                waiting++
                try {
                    var remaining = timeoutNs
                    while (busy && !closed) {
                        if (remaining <= 0L) throw ArchiveVodMediaException(
                            "Archive work admission timed out", retryable = true)
                        remaining = changed.awaitNanos(remaining)
                    }
                    if (closed) throw IOException("Archive work admission closed")
                } finally {
                    waiting--
                    // If this waiter was canceled or timed out, let its successor run.
                    changed.signalAll()
                }
            }
            if (closed) throw IOException("Archive work admission closed")
            busy = true
        } finally { lock.unlock() }
        try { return work() } finally {
            lock.lock()
            try { busy = false; changed.signalAll() } finally { lock.unlock() }
        }
    }

    override fun close() {
        lock.lock()
        try { closed = true; changed.signalAll() } finally { lock.unlock() }
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
    fun warmFollowing(number: Int, lastIndex: Int, limit: Int = RETAINED_SEGMENTS - 1): IntRange {
        require(number >= 0 && lastIndex >= number && limit in 0 until RETAINED_SEGMENTS)
        return (number + 1)..minOf(lastIndex, number + limit)
    }

    /** Android PowerManager status: none/light -> two, moderate/unknown -> one,
     * severe or worse -> no new speculative group. Foreground is never gated. */
    fun speculativeWarmLimit(thermalStatus: Int): Int = when {
        thermalStatus in 0..1 -> RETAINED_SEGMENTS - 1
        thermalStatus >= 3 -> 0
        else -> 1
    }

    /**
     * Uncached playback outranks an unrelated warmup, while normal cache hits leave it
     * running. An explicit seek also cancels stale work even if the target is cached.
     * Work already composing the requested segment is shared instead of cancelled.
     */
    fun preemptWarmup(requested: Int, cached: Boolean, active: Int?, seek: Boolean = false): Boolean =
        (seek || !cached) && active != requested

    data class NativeDemand(val positionMs: Long, val bufferedMs: Long, val speed: Float,
                            val advancing: Boolean, val observedMs: Long)

    /** A fresh clock determines how much media time remains before demand.
     * A current/uncached seek or startup retains one segment of processing
     * allowance; do not turn imminent demand into a guaranteed timeout. */
    fun nativeJobTimeoutMs(startMs: Long, durationMs: Long, demand: NativeDemand?, nowMs: Long): Long {
        val fallback = durationMs.coerceIn(1L, 5_000L)
        if (demand == null || !demand.speed.isFinite() || demand.speed <= 0f ||
            nowMs < demand.observedMs || nowMs - demand.observedMs > 2_000L) return fallback
        val elapsed = if (demand.advancing) (nowMs - demand.observedMs) * demand.speed.toDouble() else 0.0
        val untilStart = (startMs.toDouble() - demand.positionMs - elapsed).coerceAtLeast(0.0)
        val remainingBuffered = (demand.bufferedMs - elapsed).coerceAtLeast(0.0)
        val mediaAllowance = maxOf(durationMs.toDouble(), minOf(untilStart, remainingBuffered))
        return (mediaAllowance / demand.speed).toLong().coerceIn(1L, 5_000L)
    }

    /** Restrict speculative groups only, based on observed transfer sizes.
     * JVM/native foreground and manual quality choices are never gated here. */
    fun memoryWarmLimit(thermalLimit: Int, headroom: NativeStitchingDeviceBudget.Headroom?,
                        input: Long, output: Long): Int {
        if (headroom == null) return thermalLimit
        if (headroom.lowMemory || headroom.available <= headroom.threshold) return 0
        if (input <= 0 || output <= 0 || input > Int.MAX_VALUE || output > Int.MAX_VALUE)
            return minOf(thermalLimit, 1)
        val charge = requireNotNull(SpeculativeMemoryAdmission.charge(input, output))
        val spare = SpeculativeMemoryAdmission.limit(headroom)
        return minOf(thermalLimit, (spare / charge).coerceIn(0L, 2L).toInt())
    }

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
