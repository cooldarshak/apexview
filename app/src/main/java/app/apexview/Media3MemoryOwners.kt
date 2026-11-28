package app.apexview

import androidx.media3.exoplayer.upstream.DefaultAllocator

/** Tracks actual Media3 allocation across local feeds and receiver players.
 * Media3 1.11.1 exposes unused allocation bytes. Pool retention is separate
 * from DefaultLoadControl's per-player target and time minima. Weak registration
 * also avoids pinning a failed player construction. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object Media3MemoryOwners {
    data class Owner(val id: String, val role: String = "UNCLASSIFIED",
                     val videoDecoders: Int = 0, val audioDecoders: Int = 0, val items: Int = 0,
                     val forwardMs: Long = -1, val backMs: Long = -1,
                     val loads: Media3LoadLedger.Snapshot? = null,
                     val sourceAllocations: Media3SourceAllocationLedger? = null)
    private val owners = java.util.WeakHashMap<DefaultAllocator, Owner>()
    // Keep the actual allocator (not the player) visible until outstanding blocks retire.
    private val pendingRetirements = mutableSetOf<DefaultAllocator>()
    private var unverifiedRetirements = 0L
    private const val ACTIVE_RESERVE_BYTES = 2 * 1024 * 1024
    @Synchronized fun register(allocator: DefaultAllocator, id: String = "other", sourceAllocations: Media3SourceAllocationLedger? = null) {
        owners.putIfAbsent(allocator, Owner(id, sourceAllocations = sourceAllocations))
    }
    @Synchronized fun update(allocator: DefaultAllocator, role: String, video: Int, audio: Int, items: Int,
                             forwardMs: Long = -1, backMs: Long = -1, loads: Media3LoadLedger.Snapshot? = null) {
        if (allocator in pendingRetirements) return
        val prior = owners[allocator] ?: return
        owners[allocator] = Owner(prior.id, role, video, audio, items, forwardMs, backMs, loads, prior.sourceAllocations)
    }
    @Synchronized private fun ownerSnapshot() = owners.entries.map { it.key to it.value }
    fun ownerFields(): String {
        reconcileRetired()
        val entries = ownerSnapshot()
        val (pending, failures) = synchronized(this) { pendingRetirements.size to unverifiedRetirements }
        val parked = entries.filter { it.second.role == "ARCHIVE_PARKED" }
        return " media3PendingRetirements=$pending media3UnverifiedRetirements=$failures media3ParkedOwners=${parked.size} media3ParkedInUseBytes=${parked.sumOf { it.first.totalBytesAllocated.toLong() }} " +
            "media3OwnersDetail=" + entries.joinToString(",") { (allocator, owner) ->
                "${owner.id}:${owner.role}:${allocator.totalBytesAllocated}:${owner.videoDecoders}:${owner.audioDecoders}:${owner.items}"
            }.ifEmpty { "NONE" } +
            " media3FreePoolBytes=${entries.sumOf { it.first.unusedBytesAllocated.toLong() }} " +
            "media3FreePoolDetail=" + entries.joinToString(",") { (allocator, owner) -> "${owner.id}:${allocator.unusedBytesAllocated}" }.ifEmpty { "NONE" } +
            " media3BackBufferMs=NOT_EXPOSED media3SampleQueueTrackBytes=NOT_EXPOSED" +
            " media3SourceAllocationScope=SOURCE_BLOCK_CAPACITY" +
            " media3SourceAllocatedDetail=" + entries.joinToString(",") { (_, owner) ->
                "${owner.id}:${owner.sourceAllocations?.fields() ?: "UNAVAILABLE"}"
            }.ifEmpty { "NONE" } +
            " media3BufferDetail=" + entries.joinToString(",") { (_, owner) ->
                val load = owner.loads
                "${owner.id}:${owner.forwardMs}:${owner.backMs}:${load?.pending ?: -1}:" +
                    "${load?.videoBytes ?: -1}:${load?.audioBytes ?: -1}:${load?.textBytes ?: -1}:" +
                    "${load?.otherBytes ?: -1}:${load?.untracked ?: -1}"
            }.ifEmpty { "NONE" }
    }
    @Synchronized fun unregister(allocator: DefaultAllocator) {
        check(allocator !in pendingRetirements)
        owners.remove(allocator)
    }

    /** A failed Media3 release must not disappear from the owner count. Only
     * free blocks are trimmed; still-leased SampleQueue blocks stay charged. */
    fun retire(allocator: DefaultAllocator, playerReleased: Boolean) {
        val previous = synchronized(this) { owners[allocator]?.takeUnless { allocator in pendingRetirements } } ?: return
        val trim = runCatching { trimFreeBlocks(allocator, 0) }
        val empty = allocator.totalBytesAllocated == 0 && allocator.unusedBytesAllocated == 0 &&
            previous.sourceAllocations?.isEmpty() != false
        val verified = playerReleased && trim.isSuccess && empty
        synchronized(this) {
            if (!verified) unverifiedRetirements++
            if (empty) {
                pendingRetirements.remove(allocator)
                owners.remove(allocator)
            } else {
                pendingRetirements.add(allocator)
                owners[allocator] = previous.copy(role = "RETIRE_PENDING")
            }
        }
        if (!verified) android.util.Log.e("PitwallPlayback", "MEDIA3_RETIREMENT_UNVERIFIED owner=${previous.id} released=$playerReleased activeBytes=${allocator.totalBytesAllocated} freeBytes=${allocator.unusedBytesAllocated}")
    }

    private fun reconcileRetired() {
        val pending = synchronized(this) { pendingRetirements.toList() }
        for (allocator in pending) {
            if (allocator.totalBytesAllocated != 0) continue
            val source = synchronized(this) { owners[allocator]?.sourceAllocations }
            if (source?.isEmpty() == false) continue
            if (runCatching { trimFreeBlocks(allocator, 0) }.isFailure || allocator.unusedBytesAllocated != 0) continue
            synchronized(this) {
                if (pendingRetirements.remove(allocator)) owners.remove(allocator)
            }
        }
    }
    @Synchronized private fun current() = owners.keys.toList()
    fun snapshot(): Pair<Int, Long> {
        reconcileRetired()
        val active = current()
        return active.size to active.sumOf { it.totalBytesAllocated.toLong() }
    }
    /** Shrink only released allocations. The allocator monitor makes the
     * active+free measurement and retention update atomic with allocate/release.
     * DefaultLoadControl's independent loading targets are never modified.
     * Keep a warm reserve for active players to avoid repeated allocation churn. */
    fun trimFreeBlocks(allocator: DefaultAllocator, reserveBytes: Int = ACTIVE_RESERVE_BYTES): Long {
        require(reserveBytes >= 0)
        return synchronized(allocator) {
            val before = allocator.unusedBytesAllocated
            if (before <= reserveBytes) return@synchronized 0L
            val target = (allocator.totalBytesAllocated.toLong() + reserveBytes)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            allocator.setTargetBufferSize(target)
            allocator.trim()
            (before - allocator.unusedBytesAllocated).coerceAtLeast(0).toLong()
        }
    }
    fun trimUnused() {
        ownerSnapshot().forEach { (allocator, owner) ->
            val inactive = owner.role == "RETIRE_PENDING" || (owner.videoDecoders == 0 && owner.audioDecoders == 0 &&
                ((owner.role == "ARCHIVE_PARKED" && owner.items == 0) || owner.role == "REMOTE"))
            trimFreeBlocks(allocator, if (inactive) 0 else ACTIVE_RESERVE_BYTES)
        }
    }
}

/** Bounded scalar observation, not retained sample-queue bytes. All calls from
 * the Media3 application looper. Unknown/default muxed data stays OTHER. */
internal class Media3LoadLedger(private val limit: Int = 128) {
    data class Snapshot(val pending: Int, val videoBytes: Long, val audioBytes: Long,
                        val textBytes: Long, val otherBytes: Long, val untracked: Long)
    private val pending = linkedMapOf<Long, Int>()
    private val bytes = LongArray(4)
    private var untracked = 0L
    init { require(limit in 1..128) }
    fun start(id: Long, track: Int) {
        if (id in pending) return
        if (pending.size >= limit) {
            pending.remove(pending.keys.first())
            untracked++
        }
        pending[id] = track
    }
    fun finish(id: Long, downloaded: Long) {
        val track = pending.remove(id) ?: return
        val index = when (track) {
            androidx.media3.common.C.TRACK_TYPE_VIDEO -> 0
            androidx.media3.common.C.TRACK_TYPE_AUDIO -> 1
            androidx.media3.common.C.TRACK_TYPE_TEXT -> 2
            else -> 3
        }
        bytes[index] += downloaded.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE - bytes[index])
    }
    fun clearPending() { pending.clear() }
    fun snapshot() = Snapshot(pending.size, bytes[0], bytes[1], bytes[2], bytes[3], untracked)
}
