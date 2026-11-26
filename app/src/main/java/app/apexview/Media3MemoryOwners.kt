package app.apexview

import androidx.media3.exoplayer.upstream.DefaultAllocator

/** Tracks actual Media3 allocation across local feeds and receiver players.
 * DefaultAllocator.trim only releases unused blocks above its existing target;
 * do not change target bytes or time minima under pressure. Weak registration
 * also avoids pinning a failed player construction. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object Media3MemoryOwners {
    data class Owner(val id: String, val role: String = "UNCLASSIFIED",
                     val videoDecoders: Int = 0, val audioDecoders: Int = 0, val items: Int = 0,
                     val forwardMs: Long = -1, val backMs: Long = -1,
                     val loads: Media3LoadLedger.Snapshot? = null)
    private val owners = java.util.WeakHashMap<DefaultAllocator, Owner>()
    @Synchronized fun register(allocator: DefaultAllocator, id: String = "other") {
        owners.putIfAbsent(allocator, Owner(id))
    }
    @Synchronized fun update(allocator: DefaultAllocator, role: String, video: Int, audio: Int, items: Int,
                             forwardMs: Long = -1, backMs: Long = -1, loads: Media3LoadLedger.Snapshot? = null) {
        val prior = owners[allocator] ?: return
        owners[allocator] = Owner(prior.id, role, video, audio, items, forwardMs, backMs, loads)
    }
    @Synchronized private fun ownerSnapshot() = owners.entries.map { it.key to it.value }
    fun ownerFields(): String {
        val entries = ownerSnapshot()
        val parked = entries.filter { it.second.role == "ARCHIVE_PARKED" }
        return " media3ParkedOwners=${parked.size} media3ParkedInUseBytes=${parked.sumOf { it.first.totalBytesAllocated.toLong() }} " +
            "media3OwnersDetail=" + entries.joinToString(",") { (allocator, owner) ->
                "${owner.id}:${owner.role}:${allocator.totalBytesAllocated}:${owner.videoDecoders}:${owner.audioDecoders}:${owner.items}"
            }.ifEmpty { "NONE" } +
            " media3FreePoolBytes=NOT_EXPOSED media3BackBufferMs=NOT_EXPOSED media3SampleQueueTrackBytes=NOT_EXPOSED" +
            " media3BufferDetail=" + entries.joinToString(",") { (_, owner) ->
                val load = owner.loads
                "${owner.id}:${owner.forwardMs}:${owner.backMs}:${load?.pending ?: -1}:" +
                    "${load?.videoBytes ?: -1}:${load?.audioBytes ?: -1}:${load?.textBytes ?: -1}:" +
                    "${load?.otherBytes ?: -1}:${load?.untracked ?: -1}"
            }.ifEmpty { "NONE" }
    }
    @Synchronized fun unregister(allocator: DefaultAllocator) { owners.remove(allocator) }
    @Synchronized private fun current() = owners.keys.toList()
    fun snapshot(): Pair<Int, Long> {
        val active = current()
        return active.size to active.sumOf { it.totalBytesAllocated.toLong() }
    }
    fun trimUnused() { current().forEach { it.trim() } }
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
