package app.apexview.tiled

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android-runtime proof of real delivery/ByteArray ownership and bounded recovery.
 * No protected F1 media, licence, or synthetic hardware codec required.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveSegmentDeliveryAndroidTest {
    @Test fun warmAndForegroundUseOneOwnedSegmentAndSeparateReaderCopy() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 128)
        val body = ByteArray(32) { 4 }
        var publications = 0
        val delivery = ArchiveSegmentDelivery(cache, Any(), ArchiveSegmentSingleFlight(), {},
            { key -> publications++; cache.put(key, body) })
        assertNull(delivery.obtain(20, copyForCaller = false))
        val borrowed = requireNotNull(delivery.obtain(20, copyForCaller = true))
        borrowed.fill(0)
        assertEquals(1, publications)
        assertArrayEquals(ByteArray(32) { 4 }, body)
        cache.clear()
        assertArrayEquals(ByteArray(32), body)
    }

    @Test fun competingCacheEvictionsTerminateInsteadOfStarvingPlayback() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 1, maxBytes = 128)
        val guard = Any()
        var publications = 0
        val delivery = ArchiveSegmentDelivery(cache, guard, ArchiveSegmentSingleFlight(), {},
            { key ->
                publications++
                synchronized(guard) {
                    cache.put(key, ByteArray(8) { 3 })
                    cache.put(key + 1, ByteArray(8) { 5 })
                }
            })
        val failure = assertThrows(ArchiveVodMediaException::class.java) {
            delivery.obtain(20, copyForCaller = true)
        }
        assertTrue(failure.retryable)
        assertEquals(3, publications)
    }
}
