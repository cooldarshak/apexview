package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameDeadlinePolicyTest {
    @Test fun followsRefreshTransitionsBetweenPhoneAndTvModes() {
        assertEquals(20_000_000L, FrameDeadlinePolicy.durationNs(50f))
        assertEquals(16_666_666L, FrameDeadlinePolicy.durationNs(60f))
        assertEquals(8_333_333L, FrameDeadlinePolicy.durationNs(120f))
        assertEquals(16_666_666L, FrameDeadlinePolicy.durationNs(Float.NaN))
        assertEquals(16_666_666L, FrameDeadlinePolicy.durationNs(0f))
    }
}
