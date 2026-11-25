package app.apexview
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.*
import org.junit.Test
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3MemoryOwnersTest {
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
