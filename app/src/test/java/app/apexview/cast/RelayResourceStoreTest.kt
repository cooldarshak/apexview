package app.apexview.cast

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class RelayResourceStoreTest {
    @Test fun leasedRetiredResourceSurvivesPublicationWhileOtherBytesAreWiped() {
        val store = RelayResourceStore(32, 4, 2)
        val old = byteArrayOf(1, 2, 3)
        val obsolete = byteArrayOf(4, 5)
        val retained = byteArrayOf(6, 7)
        store.publish(mapOf("old" to old, "obsolete" to obsolete, "retained" to retained))
        val lease = requireNotNull(store.acquire("old"))
        store.publish(mapOf("retained" to retained, "new" to byteArrayOf(8)))
        assertArrayEquals(byteArrayOf(0, 0), obsolete)
        assertArrayEquals(byteArrayOf(6, 7), retained)
        assertNull(store.acquire("old"))
        val output = ByteArrayOutputStream()
        lease.writeTo(output, 1, 2)
        assertArrayEquals(byteArrayOf(2, 3), output.toByteArray())
        assertEquals(3L, store.snapshot().pinnedBytes)
        assertThrows(IllegalArgumentException::class.java) { store.publish(mapOf("alias" to old)) }
        lease.close(); lease.close()
        assertArrayEquals(ByteArray(3), old)
        assertEquals(0L, store.snapshot().pinnedBytes)
        store.close()
        assertArrayEquals(ByteArray(2), retained)
    }
    @Test fun byteAndReaderAdmissionIsBoundedAndCloseWaitsForLastLease() {
        val store = RelayResourceStore(8, 2, 2)
        val old = ByteArray(8) { 1 }
        store.publish(mapOf("a" to old))
        val a = requireNotNull(store.acquire("a"))
        val b = requireNotNull(store.acquire("a"))
        assertNull(store.acquire("a"))
        store.publish(mapOf("b" to ByteArray(8) { 2 }))
        a.close()
        assertNull(store.acquire("b"))
        store.close()
        assertArrayEquals(ByteArray(8) { 1 }, old)
        b.close()
        assertArrayEquals(ByteArray(8), old)
        assertEquals(0L, store.snapshot().pinnedBytes)
    }
    @Test fun malformedAndOverflowingRangesFailClosed() {
        assertEquals(RelayHttpRange(0, 99), RelayHttpRange.parse(null, 100))
        assertEquals(RelayHttpRange(5, 99), RelayHttpRange.parse("bytes=5-", 100))
        assertEquals(RelayHttpRange(5, 9), RelayHttpRange.parse("bytes=5-9", 100))
        for (range in listOf("bytes=999999999999999999999-", "bytes=0-999999999999999999999", "bytes=10-9", "bytes=-10", "bytes=0-100", "bytes=0-1,3-4"))
            assertNull(RelayHttpRange.parse(range, 100))
    }
}
