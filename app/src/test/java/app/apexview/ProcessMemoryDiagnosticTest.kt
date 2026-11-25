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
    @Test fun pinnedBackingAndLogicalPayloadAreNotAddedTwice() {
        val result = ProcessMemoryDiagnostic.fragmentFields(longArrayOf(100, 80, 70, 2, 4))
        assertTrue(result.contains("nativeFragmentLiveCapacityBytes=80"))
        assertTrue(result.contains("nativeFragmentLivePayloadBytes=70"))
        assertTrue(result.contains("nativeFragmentFreeCapacityBytes=20"))
    }
}
