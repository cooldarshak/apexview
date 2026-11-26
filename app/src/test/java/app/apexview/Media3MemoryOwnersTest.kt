package app.apexview
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.*
import org.junit.Test
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3MemoryOwnersTest {
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
}
