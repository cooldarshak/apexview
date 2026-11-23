package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validates map readiness uses exactly the same gap policy as marker drawing. */
class DriverTrackAvailabilityTest {
    @Test fun readinessMatchesRenderedPositionsAcrossBoundsAndInterpolations() {
        val track = DriverTrack(longArrayOf(1_000, 3_000, 4_000),
            floatArrayOf(0f, 10f, 20f), floatArrayOf(0f, 20f, 30f), 3)
        for (time in listOf(0L, 1_000L, 1_001L, 2_000L, 3_000L, 3_500L, 4_000L, 4_001L)) {
            assertEquals("time=$time", track.at(time) != null, track.hasPositionAt(time))
        }
        assertEquals(5f, track.at(2_000L)!!.first, 0.0001f)
        assertFalse(track.hasPositionAt(0L))
        assertTrue(track.hasPositionAt(4_000L))
    }

    @Test fun gapsLongerThanFiveSecondsAndEmptyTracksRemainUnavailable() {
        val gap = DriverTrack(longArrayOf(1_000L, 12_000L),
            floatArrayOf(1f, 2f), floatArrayOf(1f, 2f), 2)
        for (time in listOf(1_000L, 2_000L, 6_000L, 12_000L)) {
            assertFalse(gap.hasPositionAt(time))
            assertEquals(null, gap.at(time))
        }
        val empty = DriverTrack(LongArray(0), FloatArray(0), FloatArray(0), 0)
        assertFalse(empty.hasPositionAt(0))
        assertEquals(null, empty.at(0))
        val single = DriverTrack(longArrayOf(7000), floatArrayOf(.5f), floatArrayOf(.25f), 1)
        assertTrue(single.hasPositionAt(7000))
        assertEquals(.5f, single.at(7000)!!.first, 0.0001f)
        assertFalse(single.hasPositionAt(7001))
    }
}
