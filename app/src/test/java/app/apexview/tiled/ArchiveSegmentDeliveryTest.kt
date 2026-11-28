package app.apexview.tiled

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Exercises production delivery with real owned ByteArrays and concurrent callers. */
class ArchiveSegmentDeliveryTest {
    @Test fun warmupUsesOwnedCacheAndForegroundGetsIndependentCopy() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 64)
        val owned = ByteArray(12) { 0x35.toByte() }
        val productions = AtomicInteger()
        val delivery = ArchiveSegmentDelivery(cache, Any(), ArchiveSegmentSingleFlight(),
            checkOpen = {},
            composeAndPublish = { n -> productions.incrementAndGet(); cache.put(n, owned) })
        assertNull(delivery.obtain(7, copyForCaller = false))
        assertEquals(1, productions.get())
        val borrowed = requireNotNull(delivery.obtain(7, copyForCaller = true))
        assertNotSame(owned, borrowed)
        borrowed.fill(0)
        assertArrayEquals(ByteArray(12) { 0x35.toByte() }, owned)
        assertEquals(1, productions.get())
        cache.clear()
        assertArrayEquals(ByteArray(12), owned)
    }

    @Test fun foregroundJoinerSharesOnlyOneProducerEvenWithActiveWarmup() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 64)
        val lock = Any()
        val started = CountDownLatch(1)
        val joined = CountDownLatch(2)
        val release = CountDownLatch(1)
        val productions = AtomicInteger()
        val flights = ArchiveSegmentSingleFlight { joined.countDown() }
        val delivery = ArchiveSegmentDelivery(cache, lock, flights, {},
            { n ->
                productions.incrementAndGet()
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                synchronized(lock) { cache.put(n, ByteArray(8) { 7 }) }
            })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val warm = workers.submit<ByteArray?> { delivery.obtain(2, copyForCaller = false) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val playing = workers.submit<ByteArray?> { delivery.obtain(2, copyForCaller = true) }
            assertTrue(joined.await(2, TimeUnit.SECONDS))
            release.countDown()
            assertNull(warm.get(2, TimeUnit.SECONDS))
            assertArrayEquals(ByteArray(8) { 7 }, playing.get(2, TimeUnit.SECONDS))
            assertEquals(1, productions.get())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun contestedCacheFailsRetryableAfterThreePublicationsNotInfiniteLoop() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 1, maxBytes = 16)
        val lock = Any()
        var attempts = 0
        val evictionCallbacks = mutableListOf<Int>()
        val delivery = ArchiveSegmentDelivery(cache, lock, ArchiveSegmentSingleFlight(), {},
            { number ->
                attempts++
                synchronized(lock) {
                    cache.put(number, ByteArray(4) { 1 })
                    cache.put(number + 1, ByteArray(4) { 2 })
                }
            },
            onEvictionRace = { _, retry -> evictionCallbacks += retry })
        val failure = assertThrows(ArchiveVodMediaException::class.java) {
            delivery.obtain(4, copyForCaller = true)
        }
        assertTrue(failure.retryable)
        assertEquals(3, attempts)
        assertEquals(listOf(1, 2), evictionCallbacks)
    }

    @Test fun cancelledWarmupIsRecoveredOnceButRepeatedCancellationFailsClosed() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 64)
        val calls = AtomicInteger()
        val recovered = AtomicInteger()
        val delivery = ArchiveSegmentDelivery(cache, Any(), ArchiveSegmentSingleFlight(), {},
            { n ->
                if (calls.incrementAndGet() == 1) throw ArchiveWarmupCancelledException()
                cache.put(n, ByteArray(3) { 9 })
            },
            onCancelledWarmupRecovery = { recovered.incrementAndGet() })
        assertArrayEquals(ByteArray(3) { 9 }, delivery.obtain(6, copyForCaller = true))
        assertEquals(2, calls.get())
        assertEquals(1, recovered.get())
        val alwaysCancelled = ArchiveSegmentDelivery(
            BoundedArchiveFragmentCache(), Any(), ArchiveSegmentSingleFlight(), {},
            { throw ArchiveWarmupCancelledException() })
        assertThrows(ArchiveWarmupCancelledException::class.java) {
            alwaysCancelled.obtain(6, copyForCaller = true)
        }
    }

    @Test fun foregroundJoinerRecoversFromCancelledPrefetchWithoutDuplicatingProduction() {
        val lock = Any()
        val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 64)
        val started = CountDownLatch(1)
        val registered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val producers = AtomicInteger()
        val recovered = AtomicInteger()
        val delivery = ArchiveSegmentDelivery(
            cache, lock, ArchiveSegmentSingleFlight { registered.countDown() }, {},
            { key ->
                if (producers.incrementAndGet() == 1) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    throw ArchiveWarmupCancelledException()
                }
                synchronized(lock) { cache.put(key, ByteArray(6) { 0x37 }) }
            }, onCancelledWarmupRecovery = { recovered.incrementAndGet() }
        )
        val workers = Executors.newFixedThreadPool(2)
        try {
            val prefetch = workers.submit {
                assertThrows(ArchiveWarmupCancelledException::class.java) {
                    delivery.obtain(12, copyForCaller = false)
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val foreground = workers.submit<ByteArray?> { delivery.obtain(12, copyForCaller = true) }
            assertTrue(registered.await(2, TimeUnit.SECONDS))
            release.countDown()
            prefetch.get(2, TimeUnit.SECONDS)
            val copy = requireNotNull(foreground.get(2, TimeUnit.SECONDS))
            assertArrayEquals(ByteArray(6) { 0x37 }, copy)
            assertEquals(2, producers.get())
            assertEquals(1, recovered.get())
            cache.clear()
            assertArrayEquals(ByteArray(6) { 0x37 }, copy)
            copy.fill(0)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun socketTimeoutIsFailureButSeekInterruptIsCancellation() {
        val timeout = java.net.SocketTimeoutException("network timed out")
        assertEquals(ArchiveWarmupStopReason.FAILED,
            ArchiveWarmupFailurePolicy.classify(timeout, false, false))
        assertEquals(ArchiveWarmupStopReason.FAILED,
            ArchiveWarmupFailurePolicy.classify(IOException("wrapped", timeout), false, false))
        assertEquals(ArchiveWarmupStopReason.CANCELLED,
            ArchiveWarmupFailurePolicy.classify(timeout, false, true))
        assertEquals(ArchiveWarmupStopReason.CANCELLED,
            ArchiveWarmupFailurePolicy.classify(java.io.InterruptedIOException("seek interrupted"), false, false))
        assertEquals(ArchiveWarmupStopReason.CANCELLED,
            ArchiveWarmupFailurePolicy.classify(IOException("wrapped", ArchiveWarmupCancelledException()), false, false))
    }

    @Test fun failedWarmupIsVisibleAndDoesNotPoisonNextForegroundRequest() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 64)
        val calls = AtomicInteger()
        val delivery = ArchiveSegmentDelivery(cache, Any(), ArchiveSegmentSingleFlight(), {},
            { n ->
                if (calls.incrementAndGet() == 1) throw IOException("network unavailable")
                cache.put(n, byteArrayOf(1, 2, 3))
            })
        assertThrows(IOException::class.java) {
            delivery.obtain(1, copyForCaller = false)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), delivery.obtain(1, copyForCaller = true))
        assertEquals(2, calls.get())
        assertEquals(ArchiveWarmupStopReason.FAILED,
            ArchiveWarmupFailurePolicy.classify(IOException("contains confidential URL"), false, false))
        assertEquals(ArchiveWarmupStopReason.CANCELLED,
            ArchiveWarmupFailurePolicy.classify(ArchiveWarmupCancelledException(), false, false))
        assertEquals(ArchiveWarmupStopReason.CANCELLED,
            ArchiveWarmupFailurePolicy.classify(IOException("closed"), true, false))
    }

    @Test fun supersededSeekCannotRetryOrBorrowStaleLease() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 3, maxBytes = 64)
        var generation = 1
        var calls = 0
        val delivery = ArchiveSegmentDelivery(cache, Any(), ArchiveSegmentSingleFlight(), {},
            { key ->
                calls++
                generation++
                if (key == 1) throw ArchiveWarmupCancelledException()
                cache.put(key, ByteArray(8) { 7 })
            })
        assertThrows(ArchiveWarmupCancelledException::class.java) {
            delivery.obtainLease(1) { generation == 1 }
        }
        assertEquals(1, calls)
        assertThrows(ArchiveWarmupCancelledException::class.java) {
            delivery.obtainLease(2) { generation == 2 }
        }
        assertEquals(2, calls)
        // New generation can reuse valid immutable cached bytes.
        val lease = delivery.obtainLease(2) { generation == 3 }
        try { assertEquals(8, lease.size) } finally { lease.close(); cache.clear() }
        assertEquals(2, calls)
    }

    @Test fun mediaLoaderLeaseUsesSharedPublicationAndSurvivesReplacement() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 1, maxBytes = 64)
        val owned = ByteArray(12) { 7 }
        val calls = AtomicInteger()
        val delivery = ArchiveSegmentDelivery(cache, Any(), ArchiveSegmentSingleFlight(), {},
            { key -> calls.incrementAndGet(); cache.put(key, owned) })
        delivery.obtain(1, copyForCaller = false)
        val lease = delivery.obtainLease(1)
        cache.put(2, ByteArray(12) { 8 })
        val read = ByteArray(5)
        lease.readInto(read, 0, 4, 5)
        assertArrayEquals(ByteArray(5) { 7 }, read)
        assertEquals(1, calls.get())
        lease.close()
        assertArrayEquals(ByteArray(12), owned)
        cache.clear()
    }

    @Test fun fullWorkAdmissionBoundsWaitersAndCloseWakesQueuedProducer() {
        val admission = ArchiveWorkAdmission(maxWaiters = 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val active = workers.submit<Int> {
                admission.run(TimeUnit.SECONDS.toNanos(5)) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    7
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val queued = workers.submit {
                waiting.countDown()
                assertThrows(IOException::class.java) {
                    admission.run(TimeUnit.SECONDS.toNanos(5)) { error("Closed admission ran queued work") }
                }
            }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (admission.queued() == 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(1, admission.queued())
            val failure = assertThrows(ArchiveVodMediaException::class.java) {
                admission.run(0L) { error("Over-budget admission ran work") }
            }
            assertTrue(failure.retryable)
            admission.close()
            queued.get(2, TimeUnit.SECONDS)
            release.countDown()
            assertEquals(7, active.get(2, TimeUnit.SECONDS).toInt())
            assertThrows(IOException::class.java) { admission.run(0L) { Unit } }
        } finally {
            release.countDown()
            admission.close()
            workers.shutdownNow()
        }
    }
}
