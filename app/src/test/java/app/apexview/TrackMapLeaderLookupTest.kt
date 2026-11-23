package app.apexview

import org.junit.Assert.*
import org.junit.Test

/** One-pass leader lookup preserves stable ordering, ties and seek behavior. */
class TrackMapLeaderLookupTest {
    private fun laps(first: Int?, second: Int?): DriverLaps =
        DriverLaps(longArrayOf(1_000L, 2_000L),
            listOf(first, second).map { position ->
                DriverLaps.Snapshot(position = position, sectors = emptyList(),
                    lapSectors = emptyList(), lastLap = null, gap = null,
                    inPit = false, toLeader = null, ahead = null)
            }, longArrayOf())

    private fun timing() = SessionTiming(
        type = "Race", drivers = emptyMap(), tracks = emptyMap(),
        states = emptyMap(), outline = floatArrayOf(),
        hasStartFinish = false, corners = emptyList(), startUtcMs = 1_000L,
        laps = linkedMapOf("44" to laps(3, 2), "1" to laps(1, 5),
            "16" to laps(1, 1), "55" to laps(null, null))
    )

    @Test fun firstPlacedDriverWinsAnEqualPositionAndSeekRestoresPriorLeader() {
        val timing = timing()
        assertNull(leaderAt(timing, 900L))
        assertEquals("1", leaderAt(timing, 1_500L))
        assertEquals("16", leaderAt(timing, 2_500L))
        assertEquals("1", leaderAt(timing, 1_250L))
    }

    @Test fun onePassMatchesOldStableMinByForEveryTimestamp() {
        val timing = timing()
        for (whenMs in listOf(0L, 900L, 1_000L, 1_900L, 2_000L, 2_200L, 3_000L)) {
            val legacy = timing.laps.entries.mapNotNull { (number, laps) ->
                laps.at(whenMs)?.position?.let { number to it }
            }.minByOrNull { it.second }?.first
            assertEquals("parity at $whenMs", legacy, leaderAt(timing, whenMs))
        }
    }
}
