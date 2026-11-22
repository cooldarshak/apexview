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
}
