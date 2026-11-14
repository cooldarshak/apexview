package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class MapRetryTest {
    @Test
    fun theMapTriesAgainSoonThenSettlesToOnceAMinute() {
        assertEquals(listOf(3_000L, 10_000L, 30_000L, 60_000L, 60_000L, 60_000L), (0..5).map { AppState.mapRetryDelayMs(it) })
    }
 
    @Test
    fun liveSessionMetadataWinsAndPlaceholderTimelineNeverLooksLikeReplay() {
        assertEquals(true, liveTimingSourceFor(knownLive = true, hasTimeline = false, placeholder = true, playerReportsLive = false))
        assertEquals(null, liveTimingSourceFor(knownLive = false, hasTimeline = false, placeholder = false, playerReportsLive = false))
        assertEquals(null, liveTimingSourceFor(knownLive = false, hasTimeline = true, placeholder = true, playerReportsLive = false))
        assertEquals(true, liveTimingSourceFor(knownLive = false, hasTimeline = true, placeholder = false, playerReportsLive = true))
        assertEquals(false, liveTimingSourceFor(knownLive = false, hasTimeline = true, placeholder = false, playerReportsLive = false))
    }

}
