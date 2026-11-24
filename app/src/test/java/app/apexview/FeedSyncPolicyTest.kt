package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedSyncPolicyTest {
    @Test fun followsClockOnlyWhenDriftExceedsTheExistingThresholds() {
        assertEquals(FeedSyncPolicy.Correction(1f), FeedSyncPolicy.decide(150, 5000, true))
        assertEquals(FeedSyncPolicy.Correction(0.9f), FeedSyncPolicy.decide(151, 5000, true))
        assertEquals(FeedSyncPolicy.Correction(1.1f), FeedSyncPolicy.decide(-151, 5000, true))
        assertEquals(FeedSyncPolicy.Correction(0.9f), FeedSyncPolicy.decide(2000, 5000, true))
        assertEquals(FeedSyncPolicy.Correction(1f, 2999), FeedSyncPolicy.decide(2001, 5000, true))
        assertEquals(FeedSyncPolicy.Correction(1f, 7001), FeedSyncPolicy.decide(-2001, 5000, true))
    }

    @Test fun doesNotNudgePausedFollowersButStillRecoversLargeDiscontinuities() {
        assertEquals(1f, FeedSyncPolicy.decide(500, 5000, false).speedMultiplier)
        assertNull(FeedSyncPolicy.decide(500, 5000, false).seekPositionMs)
        assertEquals(0L, FeedSyncPolicy.decide(9000, 1000, false).seekPositionMs)
    }

    @Test fun extremeSeekDriftsNeverOverflowOrMoveBehindTheStart() {
        assertEquals(0L, FeedSyncPolicy.decide(Long.MAX_VALUE, 9000, true).seekPositionMs)
        assertEquals(Long.MAX_VALUE, FeedSyncPolicy.decide(Long.MIN_VALUE, 9000, true).seekPositionMs)
    }
}
