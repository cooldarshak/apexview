package app.apexview

import androidx.media3.exoplayer.upstream.DefaultAllocator

/** Tracks actual Media3 allocation across local feeds and receiver players.
 * DefaultAllocator.trim only releases unused blocks above its existing target;
 * do not change target bytes or time minima under pressure. Weak registration
 * also avoids pinning a failed player construction. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object Media3MemoryOwners {
    data class Owner(val id: String, val role: String = "UNCLASSIFIED",
                     val videoDecoders: Int = 0, val audioDecoders: Int = 0, val items: Int = 0)
    private val owners = java.util.WeakHashMap<DefaultAllocator, Owner>()
    @Synchronized fun register(allocator: DefaultAllocator, id: String = "other") {
        owners.putIfAbsent(allocator, Owner(id))
    }
    @Synchronized fun update(allocator: DefaultAllocator, role: String, video: Int, audio: Int, items: Int) {
        val prior = owners[allocator] ?: return
        owners[allocator] = Owner(prior.id, role, video, audio, items)
    }
    @Synchronized private fun ownerSnapshot() = owners.entries.map { it.key to it.value }
    fun ownerFields(): String {
        val entries = ownerSnapshot()
        val parked = entries.filter { it.second.role == "ARCHIVE_PARKED" }
        return " media3ParkedOwners=${parked.size} media3ParkedInUseBytes=${parked.sumOf { it.first.totalBytesAllocated.toLong() }} " +
            "media3OwnersDetail=" + entries.joinToString(",") { (allocator, owner) ->
                "${owner.id}:${owner.role}:${allocator.totalBytesAllocated}:${owner.videoDecoders}:${owner.audioDecoders}:${owner.items}"
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
