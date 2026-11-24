package app.apexview.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastTimingHealthPolicyTest {
    @Test fun detectsMapStallWithoutBlamingHealthyTower() {
        assertTrue(CastTimingHealthPolicy.healthy(250, 3_000, true, true, true))
        assertFalse(CastTimingHealthPolicy.healthy(2_000, 3_000, true, true, true))
    }

    @Test fun detectsTowerStallIndependentlyOfCarMotion() {
        assertTrue(CastTimingHealthPolicy.healthy(400, 8_000, true, true, true))
        assertFalse(CastTimingHealthPolicy.healthy(400, 12_000, true, true, true))
        assertTrue(CastTimingHealthPolicy.healthy(400, 12_000, true, false, true))
        assertFalse(CastTimingHealthPolicy.healthy(-1, -1, true, true, true))
        assertFalse(CastTimingHealthPolicy.healthy(-1, 50, false, true, true))
    }

    @Test fun freshPacketWithExpiredPredictionCannotClaimHealthyDriverMotion() {
        assertFalse(CastTimingHealthPolicy.healthy(
            100, 300, mapEnabled = true, towerEnabled = true,
            videoAdvancing = true, predictionSaturated = true))
        assertTrue(CastTimingHealthPolicy.healthy(
            100, 300, mapEnabled = false, towerEnabled = true,
            videoAdvancing = true, predictionSaturated = true))
        assertTrue(CastTimingHealthPolicy.healthy(
            100, 300, mapEnabled = true, towerEnabled = true,
            videoAdvancing = false, predictionSaturated = true))
    }

    @Test fun bufferingOrPausedVideoDoesNotCreateFalseAlarm() {
        assertTrue(CastTimingHealthPolicy.healthy(-1, -1, true, true, false))
        assertTrue(CastTimingHealthPolicy.healthy(-1, -1, false, false, true))
    }
}
