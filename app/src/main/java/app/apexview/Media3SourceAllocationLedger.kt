package app.apexview

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.Allocator
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Observe the real Media3 sample allocation boundary, without reflecting into
 * SampleQueue, allocating another pool, or substituting a different load control.
 * Source categories are NOT per-track payload bytes: a filtered HLS child may
 * still parse muxed tracks, and an allocation includes its unused tail.
 * One owner belongs to one non-preloading player, including all merged periods.
 */
@OptIn(UnstableApi::class)
internal class Media3SourceAllocationLedger {
    enum class Kind { SOURCE_MIXED, ARCHIVE_VIDEO, PROGRAMME_AUDIO_SOURCE, CAPTION_SOURCE, PROTECTED_COMPOSED, RECEIVER_MIXED }
    private val bytes = AtomicLongArray(Kind.entries.size)
    fun fields(): String = Kind.entries.joinToString("/") { "${it.name}:${bytes.get(it.ordinal)}" }

    fun allocator(delegate: Allocator, kind: Kind): Allocator = object : Allocator {
        override fun allocate(): Allocation {
            val result = delegate.allocate()
            bytes.addAndGet(kind.ordinal, delegate.individualAllocationLength.toLong())
            return result
        }
        override fun release(allocation: Allocation) {
            delegate.release(allocation)
            bytes.addAndGet(kind.ordinal, -delegate.individualAllocationLength.toLong())
        }
        override fun release(allocationNode: Allocator.AllocationNode) {
            // No temporary list or allocations on the release path. Count
            // before returning ownership to Media3, which may mutate nodes.
            var node: Allocator.AllocationNode? = allocationNode
            var count = 0L
            while (node != null) { count++; node = node.next() }
            delegate.release(allocationNode)
            bytes.addAndGet(kind.ordinal, -count * delegate.individualAllocationLength.toLong())
        }
        override fun trim() = delegate.trim()
        override fun getTotalBytesAllocated(): Int = delegate.totalBytesAllocated
        override fun getIndividualAllocationLength(): Int = delegate.individualAllocationLength
    }

    fun source(source: MediaSource, kind: Kind): MediaSource = object : WrappingMediaSource(source) {
        override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
            super.createPeriod(id, this@Media3SourceAllocationLedger.allocator(allocator, kind), startPositionUs)
    }

    fun factory(factory: MediaSource.Factory, kind: Kind): MediaSource.Factory = object : MediaSource.Factory by factory {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            source(factory.createMediaSource(mediaItem), kind)
    }
}
