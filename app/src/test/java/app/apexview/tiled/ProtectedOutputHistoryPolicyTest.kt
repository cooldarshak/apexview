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
        assertEquals(0, ProtectedOutputHistoryPolicy.evictions(listOf(80 * mib, 80 * mib)))
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedOutputHistoryPolicy.evictions(listOf(90 * mib, 90 * mib))
        }
    }

    @Test fun rejectsSingleOversizedFragmentAndKeepsMostRecentValidTwo() {
        val mib = 1024 * 1024
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedOutputHistoryPolicy.evictions(listOf(161 * mib))
        }
        assertEquals(1, ProtectedOutputHistoryPolicy.evictions(
            listOf(150 * mib, 40 * mib, 65 * mib)))
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedOutputHistoryPolicy.evictions(
                listOf(4 * mib, 120 * mib, 90 * mib))
        }
    }

    @Test fun rejectsInvalidPayloadSizesWithoutMediaAccess() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedOutputHistoryPolicy.evictions(listOf(0, 1))
        }
    }
}
