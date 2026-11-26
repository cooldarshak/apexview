package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessMemoryDiagnosticTest {
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
