package app.apexview
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.*
import org.junit.Test
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3MemoryOwnersTest {
    @Test fun realSourceBlocksRemainAttributedUntilReturnedToTheSameAllocator() {
        val backing = DefaultAllocator(true, 1024)
        val ledger = Media3SourceAllocationLedger()
        val video = ledger.allocator(backing, Media3SourceAllocationLedger.Kind.ARCHIVE_VIDEO)
        val audio = ledger.allocator(backing, Media3SourceAllocationLedger.Kind.PROGRAMME_AUDIO_SOURCE)
        val a = video.allocate()
        val b = video.allocate()
        val c = audio.allocate()
        assertEquals(3072, backing.totalBytesAllocated)
        assertTrue(ledger.fields().contains("ARCHIVE_VIDEO:2048"))
        assertTrue(ledger.fields().contains("PROGRAMME_AUDIO_SOURCE:1024"))
        val last = object : androidx.media3.exoplayer.upstream.Allocator.AllocationNode {
            override fun getAllocation() = b
            override fun next(): androidx.media3.exoplayer.upstream.Allocator.AllocationNode? = null
        }
        val first = object : androidx.media3.exoplayer.upstream.Allocator.AllocationNode {
            override fun getAllocation() = a
            override fun next() = last
        }
        video.release(first)
        assertEquals(1024, backing.totalBytesAllocated)
        assertTrue(ledger.fields().contains("ARCHIVE_VIDEO:0"))
        assertTrue(ledger.fields().contains("PROGRAMME_AUDIO_SOURCE:1024"))
        audio.release(c)
        assertEquals(0, backing.totalBytesAllocated)
        assertTrue(ledger.fields().contains("PROGRAMME_AUDIO_SOURCE:0"))
    }

    @Test fun boundedLoadLedgerSeparatesDownloadedBytesFromPendingOwners() {
        val loads=Media3LoadLedger(2)
        val c=androidx.media3.common.C.TRACK_TYPE_VIDEO
        loads.start(1,c); loads.start(2,androidx.media3.common.C.TRACK_TYPE_AUDIO)
        loads.start(3,androidx.media3.common.C.TRACK_TYPE_DEFAULT)
        assertEquals(2,loads.snapshot().pending)
        assertEquals(1L,loads.snapshot().untracked)
        loads.finish(1,900); loads.finish(2,200); loads.finish(2,200); loads.finish(3,400)
        assertEquals(0L,loads.snapshot().videoBytes)
        assertEquals(200L,loads.snapshot().audioBytes)
        assertEquals(400L,loads.snapshot().otherBytes)
        loads.start(4,c); loads.clearPending(); loads.finish(4,900)
        assertEquals(0,loads.snapshot().pending)
        assertEquals(0L,loads.snapshot().videoBytes)
    }
    @Test fun parkedOwnerDoesNotConflateActiveBufferBytes() {
        val parked = DefaultAllocator(true,1024)
        val host = DefaultAllocator(true,1024)
        Media3MemoryOwners.register(parked,"parked-test")
        Media3MemoryOwners.register(host,"host-test")
        val allocation = host.allocate()
        try {
            Media3MemoryOwners.update(parked,"ARCHIVE_PARKED",0,0,0)
            Media3MemoryOwners.update(host,"COMPOSED",1,1,1)
            val details=Media3MemoryOwners.ownerFields()
            assertTrue(details.contains("parked-test:ARCHIVE_PARKED:0:0:0:0"))
            assertTrue(details.contains("host-test:COMPOSED:1024:1:1:1"))
            Media3MemoryOwners.trimUnused()
            assertEquals(1024,host.totalBytesAllocated)
        } finally {
            host.release(allocation)
            Media3MemoryOwners.unregister(parked)
            Media3MemoryOwners.unregister(host)
        }
    }
    @Test fun poolTrimRetainsActiveBlocksAndExplicitReserve() {
        val allocator = DefaultAllocator(true, 1024)
        allocator.setTargetBufferSize(16 * 1024)
        val blocks = List(8) { allocator.allocate() }
        blocks.drop(1).forEach { allocator.release(it) }
        assertEquals(7 * 1024, allocator.unusedBytesAllocated)
        assertEquals(5 * 1024L, Media3MemoryOwners.trimFreeBlocks(allocator, 2 * 1024))
        assertEquals(1024, allocator.totalBytesAllocated)
        assertEquals(2 * 1024, allocator.unusedBytesAllocated)
        assertEquals(0L, Media3MemoryOwners.trimFreeBlocks(allocator, 2 * 1024))
        assertEquals(2 * 1024L, Media3MemoryOwners.trimFreeBlocks(allocator, 0))
        assertEquals(1024, allocator.totalBytesAllocated)
        val next = allocator.allocate()
        assertEquals(2 * 1024, allocator.totalBytesAllocated)
        allocator.release(next)
        allocator.release(blocks.first())
        Media3MemoryOwners.trimFreeBlocks(allocator, 0)
        assertEquals(0, allocator.unusedBytesAllocated)
    }
    @Test fun failedRetirementKeepsActualOutstandingBlockUntilLateReaderReturns() {
        val initial = Media3MemoryOwners.snapshot()
        val backing = DefaultAllocator(true, 1024)
        val ledger = Media3SourceAllocationLedger()
        val source = ledger.allocator(backing, Media3SourceAllocationLedger.Kind.ARCHIVE_VIDEO)
        Media3MemoryOwners.register(backing, "late-reader-test", ledger)
        val block = source.allocate()
        try {
            Media3MemoryOwners.retire(backing, false)
            assertEquals(initial.first + 1 to initial.second + 1024, Media3MemoryOwners.snapshot())
            assertTrue(Media3MemoryOwners.ownerFields().contains("late-reader-test:RETIRE_PENDING:1024"))
            Media3MemoryOwners.update(backing, "STALE_CALLBACK", 1, 1, 1)
            assertTrue(Media3MemoryOwners.ownerFields().contains("late-reader-test:RETIRE_PENDING:1024"))
        } finally { source.release(block) }
        assertEquals(initial, Media3MemoryOwners.snapshot())
        assertFalse(Media3MemoryOwners.ownerFields().contains("late-reader-test:"))
    }
    @Test fun trimKeepsOutstandingAllocationsAndUnregistersBothPlayers() {
        val initial = Media3MemoryOwners.snapshot()
        val a = DefaultAllocator(true,1024)
        val b = DefaultAllocator(true,1024)
        Media3MemoryOwners.register(a); Media3MemoryOwners.register(a); Media3MemoryOwners.register(b)
        val first = a.allocate(); val second = b.allocate()
        try {
            assertEquals(initial.first+2 to initial.second+2048,Media3MemoryOwners.snapshot())
            Media3MemoryOwners.trimUnused()
            assertEquals(initial.first+2 to initial.second+2048,Media3MemoryOwners.snapshot())
        } finally {
            a.release(first); b.release(second)
            Media3MemoryOwners.unregister(a); Media3MemoryOwners.unregister(b)
        }
        assertEquals(initial,Media3MemoryOwners.snapshot())
    }
    @Test fun successfulReleaseWithLateReaderIsPendingUntilReturned() {
        val backing = DefaultAllocator(true, 1024)
        val ledger = Media3SourceAllocationLedger()
        val source = ledger.allocator(backing, Media3SourceAllocationLedger.Kind.ARCHIVE_VIDEO)
        Media3MemoryOwners.register(backing, "successful-late-reader", ledger)
        val block = source.allocate()
        fun failures() = Regex("media3UnverifiedRetirements=(\\d+)").find(Media3MemoryOwners.ownerFields())!!.groupValues[1]
        val before = failures()
        try {
            Media3MemoryOwners.retire(backing, true)
            assertEquals(before, failures())
            assertTrue(Media3MemoryOwners.ownerFields().contains("successful-late-reader:RETIRE_PENDING:1024"))
        } finally { source.release(block) }
        assertFalse(Media3MemoryOwners.ownerFields().contains("successful-late-reader:"))
        assertEquals(before, failures())
    }
}
