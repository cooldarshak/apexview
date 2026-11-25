package app.apexview

import androidx.media3.exoplayer.upstream.DefaultAllocator

/** Tracks actual Media3 allocation across local feeds and receiver players.
 * DefaultAllocator.trim only releases unused blocks above its existing target;
 * do not change target bytes or time minima under pressure. Weak registration
 * also avoids pinning a failed player construction. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object Media3MemoryOwners {
    private val owners = java.util.WeakHashMap<DefaultAllocator, Unit>()
    @Synchronized fun register(allocator: DefaultAllocator) { owners[allocator] = Unit }
    @Synchronized fun unregister(allocator: DefaultAllocator) { owners.remove(allocator) }
    @Synchronized private fun current() = owners.keys.toList()
    fun snapshot(): Pair<Int, Long> {
        val active = current()
        return active.size to active.sumOf { it.totalBytesAllocated.toLong() }
    }
    fun trimUnused() { current().forEach { it.trim() } }
}
