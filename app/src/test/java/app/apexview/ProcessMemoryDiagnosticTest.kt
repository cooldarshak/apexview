package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessMemoryDiagnosticTest {
    @Test fun packedCoordinatesAreSeparateFromTimestampAndProcessTotals() {
        val fields = ProcessMemoryDiagnostic.compactTimingFields(longArrayOf(1, 264, 64, 1, 264, 64))
        assertTrue(fields.contains("nativeCompactTimingBytes=264"))
        assertTrue(fields.contains("nativeCompactCoordinateBytes=264"))
        assertEquals("nativeCompactTiming=UNAVAILABLE", ProcessMemoryDiagnostic.compactTimingFields(longArrayOf(1, 264, 64, 1, 263, 64)))
    }
    @Test fun compactTimingPayloadIsExactAndUnknownIsNotZero() {
        assertEquals("nativeCompactTiming=UNAVAILABLE", ProcessMemoryDiagnostic.compactTimingFields(null))
        assertEquals("nativeCompactTiming=UNAVAILABLE", ProcessMemoryDiagnostic.compactTimingFields(longArrayOf(2, 24, 3)))
        assertTrue(ProcessMemoryDiagnostic.compactTimingFields(longArrayOf(2, 28, 3)).contains("nativeCompactTimingBytes=28"))
        assertTrue(ProcessMemoryDiagnostic.compactTimingFields(longArrayOf(0, 0, 0)).contains("nativeCompactTimingOwners=0"))
    }
    @Test fun unknownIsNotZeroAndInvalidAccountingIsRejected() {
        assertEquals("nativeFragmentArena=UNAVAILABLE", ProcessMemoryDiagnostic.fragmentFields(null))
        assertEquals("nativeFragmentArena=UNAVAILABLE", ProcessMemoryDiagnostic.fragmentFields(longArrayOf(10, 11, 3, 1, 1)))
        assertEquals("nativeFragmentArena=UNAVAILABLE", ProcessMemoryDiagnostic.fragmentFields(longArrayOf(10, 9, 10, 1, 1)))
        assertTrue(ProcessMemoryDiagnostic.fragmentFields(longArrayOf(0, 0, 0, 0, 0)).contains("nativeFragmentCapacityBytes=0"))
    }
    @Test fun nativeEngineReservationAndActualAreDistinct() {
        assertEquals("nativeEngineArena=UNAVAILABLE", ProcessMemoryDiagnostic.engineFields(null))
        assertEquals("nativeEngineArena=UNAVAILABLE", ProcessMemoryDiagnostic.engineFields(longArrayOf(1, 10, 8, 3, 1, 8, 0, 0, 0)))
        val result = ProcessMemoryDiagnostic.engineFields(longArrayOf(1, 100, 40, 20, 2, 60, 15, 25, 40))
        assertTrue(result.contains("nativeEngineReservedBytes=100"))
        assertTrue(result.contains("nativeEnginePayloadBytes=40"))
        assertTrue(result.contains("nativeEngineBridgeBytes=20"))
        assertTrue(result.contains("nativeEnginePeakPayloadBytes=60"))
        assertTrue(result.contains("nativeEngineInputCopyBytes=15"))
    }
    @Test fun pinnedBackingAndLogicalPayloadAreNotAddedTwice() {
        val result = ProcessMemoryDiagnostic.fragmentFields(longArrayOf(100, 80, 70, 2, 4))
        assertTrue(result.contains("nativeFragmentLiveCapacityBytes=80"))
        assertTrue(result.contains("nativeFragmentLivePayloadBytes=70"))
        assertTrue(result.contains("nativeFragmentFreeCapacityBytes=20"))
    }
    @Test fun atlasIdleIsAlreadyIncludedInFreeCapacity() {
        val v=longArrayOf(100,80,70,2,0,3,4,7,400,32,20,1,2,3,0)
        val result=ProcessMemoryDiagnostic.fragmentFields(v)
        assertTrue(result.contains("nativeAtlasIdleBytes=20"))
        assertTrue(result.contains("nativeOwnedMissFit=2"))
        assertTrue(result.contains("nativeFragmentFreeCapacityBytes=20"))
        v[10]=21
        assertEquals("nativeFragmentArena=UNAVAILABLE",ProcessMemoryDiagnostic.fragmentFields(v))
    }
    @Test fun ownershipMissAndPeakCountersPreserveCapacityAccounting() {
        val v=longArrayOf(100,80,70,2,0,3,4,7,400,32,20,1,2,3,0,4,2,1,3,2,120)
        val result=ProcessMemoryDiagnostic.fragmentFields(v)
        assertTrue(result.contains("nativeOwnedEligible=4"))
        assertTrue(result.contains("nativeOwnedMissPinned=2"))
        assertTrue(result.contains("nativeFragmentPeakCapacityBytes=120"))
        v[20]=99
        assertEquals("nativeFragmentArena=UNAVAILABLE",ProcessMemoryDiagnostic.fragmentFields(v))
    }
    @Test fun observedGrowthBypassIsNotConfusedWithReuseOrUnmeasuredOldBuilds() {
        val v=longArrayOf(100,80,70,2,0,6,4,7,400,32,20,1,2,3,0,4,2,1,3,2,120,8,1,4,0,4,0,3)
        val current=ProcessMemoryDiagnostic.fragmentFields(v)
        assertTrue(current.contains("nativeAtlasResizeAttempts=4"))
        assertTrue(current.contains("nativeAtlasResizeMoved=4"))
        assertTrue(current.contains("nativeAtlasResizeBypassed=3"))
        assertTrue(current.contains("nativeOwnedOutputReuses=4"))
        val prior=ProcessMemoryDiagnostic.fragmentFields(v.copyOf(27))
        assertTrue(prior.contains("nativeAtlasResizeMoved=4"))
        assertTrue(!prior.contains("nativeAtlasResizeBypassed"))
        v[27]=-1
        assertEquals("nativeFragmentArena=UNAVAILABLE",ProcessMemoryDiagnostic.fragmentFields(v))
    }
    @Test fun nativeGrowthOutcomesAndBypassesMustReconcileWithObservedMisses() {
        val v=longArrayOf(100,80,70,2,0,6,4,7,400,32,20,1,8,3,0,4,2,1,3,2,120,8,1,4,0,4,0,3)
        assertTrue(ProcessMemoryDiagnostic.fragmentFields(v).contains("nativeAtlasResizeBypassed=3"))
        v[27]=5
        assertEquals("nativeFragmentArena=UNAVAILABLE",ProcessMemoryDiagnostic.fragmentFields(v))
        v[27]=3
        v[25]=5
        assertEquals("nativeFragmentArena=UNAVAILABLE",ProcessMemoryDiagnostic.fragmentFields(v))
        v[25]=3
        assertEquals("nativeFragmentArena=UNAVAILABLE",ProcessMemoryDiagnostic.fragmentFields(v))
        v[26]=1
        assertTrue(ProcessMemoryDiagnostic.fragmentFields(v).contains("nativeAtlasResizeFailed=1"))
        val previous=v.copyOf(27)
        assertTrue(ProcessMemoryDiagnostic.fragmentFields(previous).contains("nativeAtlasResizeMoved=3"))
        previous[23]=Long.MAX_VALUE
        assertEquals("nativeFragmentArena=UNAVAILABLE",ProcessMemoryDiagnostic.fragmentFields(previous))
    }
    @Test fun partialWorkspaceIsNotConfusedWithReservationOrUnobservedZero() {
        val boundary = longArrayOf(1, 100, 40, 0, 2, 60, 15, 25, 90, 0, 17, 1)
        val measured = ProcessMemoryDiagnostic.engineFields(boundary)
        assertTrue(measured.contains("nativeWorkspaceKnownCapacityBytes=17"))
        assertTrue(measured.contains("nativeWorkspaceScope=PARTIAL_OWNED_CAPACITY"))
        assertTrue(measured.contains("nativeEngineFutureCommitmentBytes=90"))
        boundary[10] = 0
        boundary[11] = 0
        assertTrue(ProcessMemoryDiagnostic.engineFields(boundary).contains("nativeWorkspaceKnownCapacityBytes=UNAVAILABLE"))
        boundary[10] = 17
        assertEquals("nativeEngineArena=UNAVAILABLE", ProcessMemoryDiagnostic.engineFields(boundary))
        boundary[11] = 2
        assertEquals("nativeEngineArena=UNAVAILABLE", ProcessMemoryDiagnostic.engineFields(boundary))
        assertTrue(ProcessMemoryDiagnostic.engineFields(longArrayOf(1, 100, 40, 0, 2, 60, 15, 25, 60, 1))
            .contains("nativeWorkspaceKnownCapacityBytes=UNAVAILABLE"))
    }
    @Test fun ownedTransferCountersAndRunningLowerBoundAreExplicit() {
        val fragment = ProcessMemoryDiagnostic.fragmentFields(longArrayOf(100, 80, 70, 2, 0, 3, 4, 7, 400))
        assertTrue(fragment.contains("nativeOwnedOutputAllocations=3"))
        assertTrue(fragment.contains("nativeOwnedOutputReuses=4"))
        assertTrue(fragment.contains("nativeAdoptedFragments=7"))
        assertTrue(fragment.contains("nativeAdoptedBytes=400"))
        val engine = ProcessMemoryDiagnostic.engineFields(longArrayOf(1, 100, 40, 0, 2, 60, 15, 25, 60, 1))
        assertTrue(engine.contains("nativeEnginePayloadLowerBound=true"))
    }
}
