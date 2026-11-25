package app.apexview.tiled
import org.junit.Assert.*
import org.junit.Test
class HeapFragmentLeaseTest {
    @Test fun retainedBackingSurvivesOwnerCloseAndZeroesOnlyAfterLastReader() {
        val bytes = byteArrayOf(1,2,3,4)
        val owner = HeapFragmentLease.take(bytes)
        val reader = owner.retain()
        assertSame(owner.storageIdentity,reader.storageIdentity)
        owner.close(); owner.close()
        val target = ByteArray(2)
        reader.readTo(target,0,1,2)
        assertArrayEquals(byteArrayOf(2,3),target)
        assertThrows(IllegalArgumentException::class.java) { reader.readTo(target,0,3,2) }
        reader.close(); reader.close()
        assertArrayEquals(ByteArray(4),bytes)
        assertThrows(IllegalStateException::class.java) { reader.retain() }
    }
}
