package app.apexview.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastTimingEpochPolicyTest {
    @Test fun reconnectCanStartAfterEarlierPhoneSeeks() {
        assertTrue(CastTimingEpochPolicy.adoptInitialEpoch(0, -1, false, 4))
        assertFalse(CastTimingEpochPolicy.adoptInitialEpoch(1, 50, false, 4))
        assertFalse(CastTimingEpochPolicy.adoptInitialEpoch(0, -1, true, 4))
        assertFalse(CastTimingEpochPolicy.adoptInitialEpoch(0, -1, false, -1))
    }

    @Test fun incompleteSeekBatchStillForcesFreshTableSampleOnRetry() {
        val publication = CastTimingPublicationEpoch()
        assertTrue(publication.requiresSample(0))
        publication.commit(0)
        assertFalse(publication.requiresSample(0))
        assertTrue(publication.requiresSample(1))
        // Failed table sampling or packet chunk encoding does not commit.
        repeat(3) { assertTrue(publication.requiresSample(1)) }
        publication.commit(1)
        assertFalse(publication.requiresSample(1))
        assertTrue(publication.requiresSample(2))
    }
}
