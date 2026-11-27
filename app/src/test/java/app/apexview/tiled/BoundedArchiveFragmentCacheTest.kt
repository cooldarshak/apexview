package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class BoundedArchiveFragmentCacheTest {
    @Test fun playbackClockRetiresOnlyDeliveredPastEpochAndNeverUnreadOrLeasedData() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 4, maxBytes = 64)
        val old = ByteArray(8) { 3 }
        val unread = ByteArray(8) { 4 }
        cache.put(1, old); cache.put(2, unread)
        val lease = requireNotNull(cache.readLease(1))
        cache.completeLocalDelivery(1, lease, 9)
        assertEquals(0L, cache.retirePlayed(2, 10))
        assertEquals(0L, cache.retirePlayed(2, 9))
        assertTrue(cache.contains(1))
        lease.close()
        assertFalse(cache.contains(1))
        assertTrue(cache.contains(2))
        assertArrayEquals(ByteArray(8), old)
        cache.clear()
    }

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

    @Test fun leasedDataSurvivesEvictionAndClearUntilLastReaderCloses() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 1, maxBytes = 12)
        val old = ByteArray(6) { 3 }
        cache.put(1, old)
        val first = requireNotNull(cache.readLease(1))
        val second = requireNotNull(cache.readLease(1))
        cache.put(2, ByteArray(8) { 4 })
        assertNull(cache.readCopy(1))
        assertEquals(6L, cache.leasedBytes)
        val read = ByteArray(4)
        first.readInto(read, 0, 1, 4)
        assertArrayEquals(ByteArray(4) { 3 }, read)
        first.close(); first.close()
        cache.clear()
        second.readInto(read, 0, 0, 4)
        assertArrayEquals(ByteArray(4) { 3 }, read)
        assertThrows(IllegalStateException::class.java) { first.readInto(read, 0, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { second.readInto(read, 0, 5, 2) }
        second.close()
        assertArrayEquals(ByteArray(6), old)
        assertEquals(0L, cache.leasedBytes)
    }

    @Test fun readerBudgetIsBoundedAndRecoversAfterClose() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 2, maxBytes = 10, maxReaders = 2)
        val old = ByteArray(7) { 1 }
        cache.put(1, old)
        val lease = requireNotNull(cache.readLease(1))
        val duplicate = requireNotNull(cache.readLease(1))
        assertThrows(ArchiveVodMediaException::class.java) { cache.readLease(1) }
        duplicate.close()
        cache.put(2, ByteArray(7) { 2 })
        assertThrows(ArchiveVodMediaException::class.java) { cache.readLease(2) }
        assertThrows(IllegalArgumentException::class.java) { cache.put(3, old) }
        lease.close()
        assertArrayEquals(ByteArray(7), old)
        requireNotNull(cache.readLease(2)).close()
        cache.clear()
    }

    @Test fun olderDeliveredBackingRetiresImmediatelyAfterLastReaderWithoutAnotherDelivery() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 64)
        val old = ByteArray(8) { 3 }
        val newer = ByteArray(8) { 4 }
        val warm = ByteArray(8) { 5 }
        cache.put(1, old); cache.put(2, newer); cache.put(3, warm)
        val first = requireNotNull(cache.readLease(1))
        val late = requireNotNull(cache.readLease(1))
        assertEquals(0L, cache.completeLocalDelivery(1, first, 9L))
        first.close()
        val second = requireNotNull(cache.readLease(2))
        assertEquals(0L, cache.completeLocalDelivery(2, second, 9L))
        second.close()
        assertTrue(cache.contains(1))
        val read = ByteArray(8)
        late.readInto(read, 0, 0, read.size)
        assertArrayEquals(ByteArray(8) { 3 }, read)
        late.close()
        assertFalse(cache.contains(1))
        assertArrayEquals(ByteArray(8), old)
        assertEquals(16L, cache.bytesRetained)
        assertArrayEquals(newer, cache.readCopy(2))
        assertArrayEquals(warm, cache.readCopy(3))
        cache.clear()
    }

    @Test fun outOfOrderDeliveryKeepsNewestAndSeekOrRelayInvalidationCancelsDeferredTrim() {
        for (invalidate in listOf(false, true)) {
            val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 64)
            val old = ByteArray(8) { 7 }
            val newer = ByteArray(8) { 8 }
            cache.put(1, old); cache.put(2, newer)
            val first = requireNotNull(cache.readLease(1))
            val second = requireNotNull(cache.readLease(2))
            assertEquals(0L, cache.completeLocalDelivery(2, second, 10L))
            second.close()
            // Late callback for the older segment must not evict segment 2.
            assertEquals(0L, cache.completeLocalDelivery(1, first, 10L))
            if (invalidate) cache.invalidateLocalDelivery()
            first.close()
            assertEquals(invalidate, cache.contains(1))
            assertArrayEquals(ByteArray(8) { if (invalidate) 7 else 0 }, old)
            assertArrayEquals(newer, cache.readCopy(2))
            assertEquals(0L, cache.leasedBytes)
            cache.clear()
        }
    }

    @Test fun replacingDeferredEntryDoesNotRetireNewBackingAtSameNumber() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 64)
        val old = ByteArray(8) { 6 }
        cache.put(1, old); cache.put(2, ByteArray(8) { 9 })
        val first = requireNotNull(cache.readLease(1))
        val second = requireNotNull(cache.readLease(2))
        cache.completeLocalDelivery(1, first, 11L)
        cache.completeLocalDelivery(2, second, 11L)
        second.close()
        val replacement = ByteArray(8) { 2 }
        cache.put(1, replacement)
        assertArrayEquals(ByteArray(8) { 6 }, old)
        first.close()
        assertArrayEquals(ByteArray(8), old)
        assertArrayEquals(replacement, cache.readCopy(1))
        cache.clear()
    }

    @Test fun sharedInitializationSurvivesSessionRetirementWithoutCopy() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        val published = HeapFragmentLease.take(bytes)
        val first = ArchiveFragmentLease.shared(published)
        val second = ArchiveFragmentLease.shared(published)
        assertSame(first.storageIdentity, second.storageIdentity)
        assertSame(published.storageIdentity, first.storageIdentity)
        assertTrue(published.matches(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertFalse(published.matches(byteArrayOf(1, 2, 3, 4, 5, 7)))
        val legacyCopy = published.copyBytes()
        legacyCopy.fill(0)
        published.close()
        assertThrows(IllegalStateException::class.java) { ArchiveFragmentLease.shared(published) }
        first.close()
        first.close()
        val range = ByteArray(4)
        second.readInto(range, 0, 1, 4)
        assertArrayEquals(byteArrayOf(2, 3, 4, 5), range)
        val output = java.io.ByteArrayOutputStream()
        second.writeTo(output, 2, 3)
        assertArrayEquals(byteArrayOf(3, 4, 5), output.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { second.readInto(range, 0, 5, 2) }
        second.close()
        assertArrayEquals(ByteArray(6), bytes)
        assertThrows(IllegalStateException::class.java) { second.readInto(range, 0, 0, 1) }
    }

}
