package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class BoundedArchiveFragmentCacheTest {
    @Test fun boundedByBytesEvenWhenThreeEntriesWouldFit() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 12)
        val eldest = ByteArray(7) { 7 }
        val next = ByteArray(6) { 6 }
        cache.put(1, eldest)
        cache.put(2, next)
        assertEquals(1, cache.size)
        assertEquals(6L, cache.bytesRetained)
        assertArrayEquals(ByteArray(7), eldest)
        assertArrayEquals(next, cache.readCopy(2))
    }

    @Test fun lruAccessProtectsForegroundSegmentAndEvictionZeroesOwnership() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 30)
        val one = ByteArray(8) { 1 }; val two = ByteArray(8) { 2 }
        cache.put(1, one); cache.put(2, two); cache.put(3, ByteArray(8) { 3 })
        val borrower = requireNotNull(cache.readCopy(1))
        cache.put(4, ByteArray(8) { 4 })
        assertNull(cache.readCopy(2))
        assertTrue(two.all { it == 0.toByte() })
        assertTrue(one.all { it == 1.toByte() })
        cache.clear()
        assertTrue(one.all { it == 0.toByte() })
        assertTrue(borrower.all { it == 1.toByte() })
        borrower.fill(0)
        assertEquals(0L, cache.bytesRetained)
        assertEquals(0, cache.size)
    }

    @Test fun replacingAndClearingZeroBuffersWithoutMutatingBorrowedCopies() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 2, maxBytes = 20)
        val first = ByteArray(6) { 5 }
        cache.put(3, first)
        val borrowed = requireNotNull(cache.readCopy(3))
        val latest = ByteArray(8) { 4 }
        cache.put(3, latest)
        assertArrayEquals(ByteArray(6), first)
        assertEquals(8L, cache.bytesRetained)
        assertTrue(borrowed.all { it == 5.toByte() })
        cache.clear()
        assertArrayEquals(ByteArray(8), latest)
    }

    @Test fun refusesOversizedAndAliasedOwnership() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 2, maxBytes = 8)
        assertThrows(IllegalArgumentException::class.java) { cache.put(0, ByteArray(9)) }
        val same = ByteArray(8) { 9 }
        cache.put(0, same)
        assertThrows(IllegalArgumentException::class.java) { cache.put(1, same) }
        assertEquals(8L, cache.bytesRetained)
        assertEquals(1, cache.size)
    }
}
