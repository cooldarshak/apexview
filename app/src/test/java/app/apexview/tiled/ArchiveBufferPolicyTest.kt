package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ArchiveBufferPolicyTest {
    @Test fun localDeliveryRetiresOnlyOlderDeliveredUnpinnedBackingInSameEpoch() {
        val cache = BoundedArchiveFragmentCache(6, 100)
        val old = byteArrayOf(1, 2)
        val pinned = byteArrayOf(3, 4)
        val unread = byteArrayOf(5, 6)
        val next = byteArrayOf(7, 8)
        val future = byteArrayOf(9, 10)
        cache.put(0, old); cache.put(1, pinned); cache.put(2, unread)
        cache.put(3, next); cache.put(4, future)
        val a = requireNotNull(cache.readLease(0))
        val b = requireNotNull(cache.readLease(1))
        val c = requireNotNull(cache.readLease(3))
        assertEquals(0L, cache.completeLocalDelivery(0, a, 7))
        assertEquals(0L, cache.completeLocalDelivery(1, b, 7))
        // The final reader now retires an older proven delivery immediately.
        assertTrue(cache.contains(0))
        assertArrayEquals(byteArrayOf(1, 2), old)
        a.close()
        assertFalse(cache.contains(0))
        assertArrayEquals(byteArrayOf(0, 0), old)
        assertEquals(2L, cache.memorySnapshot().deferredRetiredBytes)
        assertEquals(1L, cache.memorySnapshot().deferredRetiredEntries)
        assertEquals(0L, cache.completeLocalDelivery(3, c, 8))
        assertTrue(cache.contains(1))
        assertEquals(0L, cache.completeLocalDelivery(3, c, 7))
        assertTrue(cache.contains(1)); assertTrue(cache.contains(2))
        assertTrue(cache.contains(3)); assertTrue(cache.contains(4))
        val copy = ByteArray(2); b.readInto(copy, 0, 0, 2)
        assertArrayEquals(byteArrayOf(3, 4), copy)
        b.close()
        assertFalse(cache.contains(1))
        assertArrayEquals(byteArrayOf(0, 0), pinned)
        assertEquals(4L, cache.memorySnapshot().deferredRetiredBytes)
        assertEquals(2L, cache.memorySnapshot().deferredRetiredEntries)
        c.close()
        assertTrue(cache.contains(2)); assertTrue(cache.contains(3)); assertTrue(cache.contains(4))
        cache.clear()
    }

    @Test fun obsoleteDeliveryCannotRetireReplacementBacking() {
        val cache = BoundedArchiveFragmentCache(4, 100)
        cache.put(0, byteArrayOf(1)); cache.put(1, byteArrayOf(2))
        val a = requireNotNull(cache.readLease(0)); cache.completeLocalDelivery(0, a, 1); a.close()
        val stale = requireNotNull(cache.readLease(1))
        cache.put(1, byteArrayOf(3))
        assertEquals(0L, cache.completeLocalDelivery(1, stale, 1))
        assertTrue(cache.contains(0)); assertArrayEquals(byteArrayOf(3), cache.readCopy(1))
        stale.close(); cache.clear()
    }

    @Test fun pressureTrimOnlyRetiresUnpinnedBehindTheRequestedSegment() {
        val cache = BoundedArchiveFragmentCache(5, 100)
        val past = byteArrayOf(1, 2)
        val pinned = byteArrayOf(3, 4)
        val active = byteArrayOf(5, 6)
        val future = byteArrayOf(7, 8)
        cache.put(0, past); cache.put(1, pinned); cache.put(2, active); cache.put(3, future)
        val lease = requireNotNull(cache.readLease(1))
        assertEquals(2L, cache.trimBehind(2))
        assertTrue(past.all { it == 0.toByte() })
        assertTrue(pinned.contentEquals(byteArrayOf(3, 4)))
        assertTrue(active.contentEquals(byteArrayOf(5, 6)))
        assertTrue(future.contentEquals(byteArrayOf(7, 8)))
        lease.close()
        assertEquals(2L, cache.trimBehind(2))
        cache.clear()
    }

    @Test fun retiredPinnedBytesCountOnceAndOnlyGateSpeculativeWork() {
        val cache = BoundedArchiveFragmentCache(maxEntries = 1, maxBytes = 10L)
        cache.put(1, ByteArray(6) { 7 })
        val lease = requireNotNull(cache.readLease(1))
        assertEquals(6L, cache.memorySnapshot().unique)
        cache.put(2, ByteArray(4) { 9 })
        assertEquals(10L, cache.memorySnapshot().unique)
        assertEquals(6L, cache.memorySnapshot().retiredPinned)
        assertFalse(cache.canSpeculate(3, 1L))
        assertTrue(cache.canSpeculate(2, 1L))
        val copy = ByteArray(6)
        lease.readInto(copy, 0, 0, copy.size)
        assertArrayEquals(ByteArray(6) { 7 }, copy)
        lease.close()
        assertEquals(4L, cache.memorySnapshot().unique)
        assertTrue(cache.canSpeculate(3, 6L))
        cache.clear()
        assertEquals(0L, cache.memorySnapshot().unique)
    }

    @Test fun nativeDemandUsesFreshPlaybackClockAndMemoryLimitsOnlySpeculation() {
        val demand = ArchiveBufferPolicy.NativeDemand(1000L, 9000L, 1.5f, true, 100L)
        assertEquals(2000L, ArchiveBufferPolicy.nativeJobTimeoutMs(1000L, 3000L, demand, 100L))
        assertEquals(4000L, ArchiveBufferPolicy.nativeJobTimeoutMs(7000L, 3000L, demand, 100L))
        assertEquals(3000L, ArchiveBufferPolicy.nativeJobTimeoutMs(7000L, 3000L, demand, 2201L))
        val mib = 1024L * 1024
        val roomy = NativeStitchingDeviceBudget.Headroom(false, false, 2048*mib, 128*mib, 512*mib)
        assertEquals(2, ArchiveBufferPolicy.memoryWarmLimit(2, roomy, 6*mib, 8*mib))
        assertEquals(1, ArchiveBufferPolicy.memoryWarmLimit(1, roomy, 6*mib, 8*mib))
        assertEquals(0, ArchiveBufferPolicy.memoryWarmLimit(2, roomy.copy(lowMemory = true), 6*mib, 8*mib))
        assertEquals(0, ArchiveBufferPolicy.memoryWarmLimit(2, roomy.copy(jvmHeadroom = 8*mib), 6*mib, 8*mib))
        assertEquals(2, ArchiveBufferPolicy.memoryWarmLimit(2, null, 6*mib, 8*mib))
    }

    @Test fun allEightFeedsShareOneFiniteNetworkCaptureDeadline() {
        val started = -40_000_000_000L
        val limitMs = ArchiveFetchDeadline.GROUP_TIMEOUT_MS
        val budgetNs = TimeUnit.MILLISECONDS.toNanos(limitMs)
        assertEquals(budgetNs, ArchiveFetchDeadline.remainingNs(started, started))
        assertEquals(TimeUnit.SECONDS.toNanos(5),
            ArchiveFetchDeadline.remainingNs(started, started + TimeUnit.SECONDS.toNanos(20)))
        // Source seven cannot reset a 25-second timeout after six prior futures.
        assertEquals(0L, ArchiveFetchDeadline.remainingNs(started, started + budgetNs))
        assertEquals(0L, ArchiveFetchDeadline.remainingNs(started, started + budgetNs + 1L))
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveFetchDeadline.remainingNs(started, started, 0)
        }
    }

    @Test fun failedRegistrationDoesNotStrandFutureOrPoisonForegroundRetry() {
        val registrations = AtomicInteger(0)
        val coordinator = ArchiveSegmentSingleFlight {
            if (registrations.incrementAndGet() == 1) throw IOException("registration observer failed")
        }
        assertThrows(IOException::class.java) {
            coordinator.run(17) { fail("registration failed before production") }
        }
        coordinator.run(17) { /* foreground retry must own a new flight */ }
        assertEquals(2, registrations.get())
    }

    @Test fun failedJoinerRegistrationMustNotCancelExistingProducer() {
        val producerReady = CountDownLatch(1)
        val releaseProducer = CountDownLatch(1)
        val registrations = AtomicInteger(0)
        val coordinator = ArchiveSegmentSingleFlight {
            if (registrations.incrementAndGet() == 2) throw IOException("joiner registration failed")
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val producer = pool.submit {
                coordinator.run(18) {
                    producerReady.countDown()
                    check(releaseProducer.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(producerReady.await(2, TimeUnit.SECONDS))
            assertThrows(java.util.concurrent.ExecutionException::class.java) {
                pool.submit {
                    coordinator.run(18) { fail("a joiner may not replace active production") }
                }.get(2, TimeUnit.SECONDS)
            }
            releaseProducer.countDown()
            producer.get(2, TimeUnit.SECONDS)
            coordinator.run(18) { /* completed old flight must be released */ }
        } finally {
            releaseProducer.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun foregroundDoesNotWaitForUnrelatedPrefetch() {
        val coordinator = ArchiveSegmentSingleFlight()
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val prefetch = pool.submit { coordinator.run(7) {
                slowStarted.countDown()
                check(releaseSlow.await(5, TimeUnit.SECONDS))
            } }
            assertTrue(slowStarted.await(2, TimeUnit.SECONDS))
            val foreground = pool.submit { coordinator.run(8) {} }
            foreground.get(1, TimeUnit.SECONDS)
            assertFalse(prefetch.isDone)
            releaseSlow.countDown()
            prefetch.get(2, TimeUnit.SECONDS)
        } finally {
            releaseSlow.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun concurrentSameSegmentSharesOneProduction() {
        val registered = CountDownLatch(4)
        val coordinator = ArchiveSegmentSingleFlight { registered.countDown() }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val producer = pool.submit { coordinator.run(3) {
                count.incrementAndGet(); started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            } }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val joining = (1..3).map { pool.submit { coordinator.run(3) { count.incrementAndGet() } } }
            assertTrue(registered.await(2, TimeUnit.SECONDS))
            release.countDown()
            producer.get(2, TimeUnit.SECONDS)
            joining.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, count.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun cancelledProducerDoesNotPoisonLaterForegroundRegistration() {
        val joined = CountDownLatch(2)
        val coordinator = ArchiveSegmentSingleFlight { joined.countDown() }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        val attempts = AtomicInteger()
        try {
            val cancelled = pool.submit {
                assertThrows(ArchiveWarmupCancelledException::class.java) {
                    coordinator.run(5) {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        throw ArchiveWarmupCancelledException()
                    }
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val joining = pool.submit {
                assertThrows(ArchiveWarmupCancelledException::class.java) {
                    coordinator.run(5) { fail("joined flight must not duplicate production") }
                }
            }
            assertTrue(joined.await(2, TimeUnit.SECONDS))
            release.countDown()
            cancelled.get(2, TimeUnit.SECONDS)
            joining.get(2, TimeUnit.SECONDS)
            pool.submit { coordinator.run(5) { attempts.incrementAndGet() } }.get(2, TimeUnit.SECONDS)
            assertEquals(1, attempts.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun shutdownUnblocksJoiningForegroundWithoutWaitingForStalledProducer() {
        val registered = CountDownLatch(2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = ArchiveSegmentSingleFlight { registered.countDown() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val producer = pool.submit {
                assertThrows(IOException::class.java) {
                    coordinator.run(42) {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val waiter = pool.submit {
                assertThrows(IOException::class.java) {
                    coordinator.run(42) { fail("closed join must never spawn second producer") }
                }
            }
            assertTrue(registered.await(2, TimeUnit.SECONDS))
            coordinator.close()
            waiter.get(1, TimeUnit.SECONDS)
            assertFalse(producer.isDone)
            release.countDown()
            producer.get(2, TimeUnit.SECONDS)
            // A fresh foreground request must not resurrect the closed flight.
            assertThrows(IOException::class.java) { coordinator.run(42) {} }
            coordinator.close() // idempotent
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun failureIsSharedAndNextAttemptCanRetry() {
        val coordinator = ArchiveSegmentSingleFlight()
        val attempts = AtomicInteger()
        assertThrows(IOException::class.java) { coordinator.run(2) {
            attempts.incrementAndGet(); throw IOException("bad fragment")
        } }
        coordinator.run(2) { attempts.incrementAndGet() }
        assertEquals(2, attempts.get())
    }

    @Test fun seekCancellationAbortsStaleProductionAndAllowsAnotherSegment() {
        val registered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val coordinator = ArchiveSegmentSingleFlight()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val obsolete = pool.submit {
                try {
                    coordinator.run(3) {
                        registered.countDown()
                        Thread.sleep(10_000)
                    }
                } finally { exited.countDown() }
            }
            assertTrue(registered.await(2, TimeUnit.SECONDS))
            assertTrue(obsolete.cancel(true))
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            // The seek target is unrelated and must be usable without the cancelled producer.
            pool.submit { coordinator.run(50) {} }.get(2, TimeUnit.SECONDS)
            // A failed/cancelled attempt does not permanently poison this key.
            pool.submit { coordinator.run(3) {} }.get(2, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }

    @Test fun unrelatedCallerCompletionDoesNotCancelActiveProduction() {
        val registered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = ArchiveSegmentSingleFlight()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val active = pool.submit { coordinator.run(11) {
                registered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            } }
            assertTrue(registered.await(2, TimeUnit.SECONDS))
            pool.submit { coordinator.run(12) {} }.get(1, TimeUnit.SECONDS)
            release.countDown()
            active.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun readAheadStaysWithinThreeSegmentsAndArchiveEnd() {
        assertEquals(1..2, ArchiveBufferPolicy.warmFollowing(0, 12))
        assertEquals(12..12, ArchiveBufferPolicy.warmFollowing(11, 12))
        assertTrue(ArchiveBufferPolicy.warmFollowing(12, 12).isEmpty())
    }

    @Test fun playbackRequestPreemptsAnUnrelatedExpensiveWarmup() {
        assertTrue(ArchiveBufferPolicy.preemptWarmup(42, cached = false, active = 41))
        assertTrue(ArchiveBufferPolicy.preemptWarmup(88, cached = false, active = null))
        assertFalse(ArchiveBufferPolicy.preemptWarmup(41, cached = false, active = 41))
        assertFalse(ArchiveBufferPolicy.preemptWarmup(88, cached = true, active = 41))
        assertTrue(ArchiveBufferPolicy.preemptWarmup(88, cached = true, active = 41, seek = true))
        assertFalse(ArchiveBufferPolicy.preemptWarmup(41, cached = true, active = 41, seek = true))
    }

    @Test fun archiveSeekCancelsUncommittedCaptureAndRestartsAtTarget() {
        assertTrue(ArchiveBufferPolicy.restartPreflightOnSeek(true, true, false, true))
        assertFalse(ArchiveBufferPolicy.restartPreflightOnSeek(false, true, false, true))
        assertFalse(ArchiveBufferPolicy.restartPreflightOnSeek(true, false, false, true))
        assertFalse(ArchiveBufferPolicy.restartPreflightOnSeek(true, true, true, true))
        assertFalse(ArchiveBufferPolicy.restartPreflightOnSeek(true, true, false, false))
        // The S22 Ultra Build 694 log: 0ms -> 920734ms during first capture.
        assertTrue(ArchiveBufferPolicy.preflightClockMoved(0, 920_734))
        assertTrue(ArchiveBufferPolicy.preflightClockMoved(920_734, 0))
        assertFalse(ArchiveBufferPolicy.preflightClockMoved(920_734, 922_000))
        assertFalse(ArchiveBufferPolicy.preflightClockMoved(920_734, 922_734))
        assertTrue(ArchiveBufferPolicy.preflightClockMoved(920_734, 922_735))
        assertFalse(ArchiveBufferPolicy.preflightClockInvalidated(10_000, 25_000, 15_000, 1f, true))
        assertFalse(ArchiveBufferPolicy.preflightClockInvalidated(10_000, 32_000, 15_000, 1.5f, true))
        assertTrue(ArchiveBufferPolicy.preflightClockInvalidated(10_000, 25_000, 15_000, 1f, false))
        assertTrue(ArchiveBufferPolicy.preflightClockInvalidated(10_000, 900_000, 15_000, 1f, true))
        assertTrue(ArchiveBufferPolicy.preflightClockInvalidated(10_000, 0, 15_000, 1f, true))
    }

    @Test fun anExpiredPreflightWindowCannotReplaceThePlayingSources() {
        val captured = CmafWindowTiming(11_520_000, 17_280_000)
        assertFalse(ArchiveBufferPolicy.ready(captured, 19_600_000, 120_000_000, minimumLeadUs = 0))
        assertFalse(ArchiveBufferPolicy.ready(CmafWindowTiming(23_040_000, 28_800_000), 19_600_000, 120_000_000))
    }

    @Test fun protectedRollingPrebufferUsesActualPlaybackSpeed() {
        assertEquals(6_000_000L, ArchiveBufferPolicy.protectedLeadUs(1f))
        assertEquals(9_000_000L, ArchiveBufferPolicy.protectedLeadUs(1.5f))
        assertEquals(12_000_000L, ArchiveBufferPolicy.protectedLeadUs(2f))
        assertEquals(6_000_000L, ArchiveBufferPolicy.protectedLeadUs(Float.NaN))
        val clock = 20_000_000L
        val oneFragment = CmafWindowTiming(17_280_000, 23_040_000)
        val twoFragments = CmafWindowTiming(17_280_000, 34_560_000)
        assertFalse(ArchiveBufferPolicy.ready(oneFragment, clock,
            100_000_000, ArchiveBufferPolicy.protectedLeadUs(1.5f)))
        assertTrue(ArchiveBufferPolicy.ready(twoFragments, clock,
            100_000_000, ArchiveBufferPolicy.protectedLeadUs(1.5f)))
        // A short remainder must not hang forever waiting for media past EOF.
        assertTrue(ArchiveBufferPolicy.ready(CmafWindowTiming(0, 22_000_000),
            clock, 22_000_000, ArchiveBufferPolicy.protectedLeadUs(2f)))
    }

    @Test fun startupFreezesTheClockUntilQueuedDataHasRealLead() {
        val clock = 27_600_000L
        val first = CmafWindowTiming(11_520_000, 28_800_000)
        assertTrue(ArchiveBufferPolicy.ready(first, clock, 120_000_000, minimumLeadUs = 0))
        assertFalse(ArchiveBufferPolicy.ready(first, clock, 120_000_000))
        assertFalse(ArchiveBufferPolicy.audioMayPlay(true, true, fillingStartupBuffer = true))
        val prefetched = CmafWindowTiming(17_280_000, 34_560_000)
        assertTrue(ArchiveBufferPolicy.ready(prefetched, clock, 120_000_000))
        assertTrue(ArchiveBufferPolicy.audioMayPlay(true, true))
        assertFalse(ArchiveBufferPolicy.audioMayPlay(true, false)) // a later real video stall
        assertFalse(ArchiveBufferPolicy.audioMayPlay(false, true)) // the user's pause remains authoritative
    }

    @Test fun prefetchesTwoFollowingSegmentsWithoutEvictingTheCurrentSegment() {
        val candidates = (0L..8L).map { ArchiveDashSegmentSelector.Candidate(it, it * 5_760_000L) }
        val clock = 16_000_000L
        val lead = ArchiveBufferPolicy.maximumLeadUs(candidates, clock)
        assertEquals(23_040_000L, clock + lead)
        val next = ArchiveDashSegmentSelector.selectAligned(listOf(candidates, candidates), clock,
            previousStartUs = 11_520_000, toleranceUs = 0, maximumLeadUs = lead)!!
        assertEquals(17_280_000L, next.first().absoluteStartUs)
        val final = ArchiveDashSegmentSelector.selectAligned(listOf(candidates, candidates), clock,
            previousStartUs = 17_280_000, toleranceUs = 0, maximumLeadUs = lead)!!
        assertEquals(23_040_000L, final.first().absoluteStartUs)
        assertNull(ArchiveDashSegmentSelector.selectAligned(listOf(candidates, candidates), clock,
            previousStartUs = 23_040_000, toleranceUs = 0, maximumLeadUs = lead))
    }

    @Test fun doesNotRequireThreeSecondsPastTheEndOfTheArchive() {
        assertTrue(ArchiveBufferPolicy.ready(CmafWindowTiming(0, 2_000_000), 1_500_000, 2_000_000))
        assertFalse(ArchiveBufferPolicy.ready(CmafWindowTiming(0, 2_000_000), 2_000_000, 2_000_000))
    }

    @Test fun largeMediaTimestampsAreScaledWithoutOverflow() {
        val ticks = 1_700_000_000L * 90_000L
        assertEquals(1_700_000_000_000_000L, CmafWindowTiming.ticksToUs(ticks, 90_000))
    }

    @Test fun thermalBudgetLimitsOnlySpeculativeFollowingSegments() {
        assertEquals(listOf(11, 12), ArchiveBufferPolicy.warmFollowing(10, 20, ArchiveBufferPolicy.speculativeWarmLimit(0)).toList())
        assertEquals(listOf(11), ArchiveBufferPolicy.warmFollowing(10, 20, ArchiveBufferPolicy.speculativeWarmLimit(2)).toList())
        assertEquals(listOf(11), ArchiveBufferPolicy.warmFollowing(10, 20, ArchiveBufferPolicy.speculativeWarmLimit(-1)).toList())
        assertTrue(ArchiveBufferPolicy.warmFollowing(10, 20, ArchiveBufferPolicy.speculativeWarmLimit(3)).isEmpty())
        assertTrue(ArchiveBufferPolicy.warmFollowing(20, 20, ArchiveBufferPolicy.speculativeWarmLimit(6)).isEmpty())
    }
}
