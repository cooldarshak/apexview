package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class ProtectedOutputHistoryPolicyTest {
    @Test fun limitsOrdinaryFragmentsToThree() {
        assertEquals(0, ProtectedOutputHistoryPolicy.evictions(listOf(5, 5, 5)))
        assertEquals(1, ProtectedOutputHistoryPolicy.evictions(listOf(5, 5, 5, 5)))
    }

    @Test fun largeEightFeedAtlasHistoryUsesByteBudget() {
        val mib = 1024 * 1024
        assertEquals(1, ProtectedOutputHistoryPolicy.evictions(listOf(64 * mib, 64 * mib, 64 * mib)))
        assertEquals(2, ProtectedOutputHistoryPolicy.evictions(listOf(40 * mib, 40 * mib, 40 * mib, 40 * mib, 40 * mib)))
    }

    @Test fun twoProtectedFragmentsRemainForStartupLead() {
        val mib = 1024 * 1024
        assertEquals(0, ProtectedOutputHistoryPolicy.evictions(listOf(90 * mib, 90 * mib)))
    }

    @Test fun rejectsInvalidPayloadSizesWithoutMediaAccess() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedOutputHistoryPolicy.evictions(listOf(0, 1))
        }
    }
}
