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

    @Test fun producerAndRollingRelayShareOneBackingUntilTheLastReaderCloses() {
        val budget = RelayMediaBudget(6)
        val store = RelayResourceStore(16)
        val bytes = byteArrayOf(10, 20, 30)
        val audio = budget.own(bytes)
        store.publish(mapOf("manifest.mpd" to byteArrayOf(1)), mapOf("audio.m4s" to audio))
        val lease = requireNotNull(store.acquire("audio.m4s"))
        store.publish(mapOf("manifest.mpd" to byteArrayOf(2)), mapOf("audio.m4s" to audio))
        assertEquals(3L, budget.bytesInUse())
        audio.close()
        store.publish(mapOf("manifest.mpd" to byteArrayOf(3)))
        assertNull(store.acquire("audio.m4s"))
        val output = ByteArrayOutputStream()
        lease.writeTo(output, 0, 3)
        assertArrayEquals(byteArrayOf(10, 20, 30), output.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { budget.own(ByteArray(4)) }
        store.close()
        assertArrayEquals(byteArrayOf(10, 20, 30), bytes)
        lease.close()
        assertEquals(0L, budget.bytesInUse())
        assertArrayEquals(ByteArray(3), bytes)
    }

    @Test fun failedPublicationRollsBackSharedReferencesAndKeepsTheCurrentWindow() {
        val budget = RelayMediaBudget(12)
        val store = RelayResourceStore(16)
        val current = byteArrayOf(1)
        store.publish(mapOf("manifest.mpd" to current))
        val next = byteArrayOf(2, 3)
        val first = budget.own(next)
        val retired = budget.own(byteArrayOf(4))
        retired.close()
        assertThrows(IllegalStateException::class.java) {
            store.publish(emptyMap(), mapOf("next" to first, "retired" to retired))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.publish(mapOf("aliased" to next), mapOf("next" to first))
        }
        assertEquals(1L, store.snapshot().currentBytes)
        assertArrayEquals(byteArrayOf(1), current)
        first.close()
        assertArrayEquals(ByteArray(2), next)
        assertEquals(0L, budget.bytesInUse())
        store.close()
    }
}
