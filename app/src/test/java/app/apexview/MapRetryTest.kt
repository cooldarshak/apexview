package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class MapRetryTest {
    @Test
    fun theMapTriesAgainSoonThenSettlesToOnceAMinute() {
        assertEquals(listOf(3_000L, 10_000L, 30_000L, 60_000L, 60_000L, 60_000L), (0..5).map { AppState.mapRetryDelayMs(it) })
    }
 
    @Test
    fun tvMapControlsRemainAvailableInDockedLayout() {
        assertEquals(true, shouldShowMapControls(isTv = true, arranging = false, floating = false, docked = true))
        assertEquals(false, shouldShowMapControls(isTv = false, arranging = false, floating = false, docked = true))
        assertEquals(true, shouldShowMapControls(isTv = false, arranging = false, floating = true, docked = false))
        assertEquals(true, shouldShowMapControls(isTv = false, arranging = true, floating = false, docked = false))
    }

    @Test
    fun archiveUsesCatalogSessionStartWhenTvHasNoProgramClock() {
        assertEquals(1_000L, archiveLookupTime(watchedUtc = 1_000L, sessionStartUtcMs = 2_000L))
        assertEquals(2_000L, archiveLookupTime(watchedUtc = null, sessionStartUtcMs = 2_000L))
        assertEquals(null, archiveLookupTime(watchedUtc = null, sessionStartUtcMs = null))
    }

    @Test
    fun tvDecoderBudgetReservesOneCodecAndHandlesUnknownCapabilitiesSafely() {
        assertEquals(1, reportedVideoDecoderBudget(null, isTv = true))
        assertEquals(1, reportedVideoDecoderBudget(-1, isTv = true))
        assertEquals(1, reportedVideoDecoderBudget(1, isTv = true))
        assertEquals(1, reportedVideoDecoderBudget(2, isTv = true))
        assertEquals(2, reportedVideoDecoderBudget(3, isTv = true))
        assertEquals(3, reportedVideoDecoderBudget(4, isTv = true))
        assertEquals(4, reportedVideoDecoderBudget(16, isTv = true))
        assertEquals(4, reportedVideoDecoderBudget(1, isTv = false))
    }

    @Test
    fun catalogReplayMetadataAvoidsWaitingForADeviceSpecificPlayerClock() {
        assertEquals(false, catalogTimingSource(knownLive = false, knownArchive = true, completedLive = false))
        assertEquals(false, catalogTimingSource(knownLive = false, knownArchive = false, completedLive = true))
        assertEquals(true, catalogTimingSource(knownLive = true, knownArchive = false, completedLive = false))
        assertEquals(null, catalogTimingSource(knownLive = false, knownArchive = false, completedLive = false))
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
