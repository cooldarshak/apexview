package app.apexview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplayCoreCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun preservesIndexedReplayAndRejectsIncompleteFiles() {
        val cache = ReplayCoreCache(temporary.root, 17)
        val sector = SectorCell("28.002", Pace.GREEN, listOf(2049, 2051), "28.800")
        val snapshot = DriverLaps.Snapshot(
            4, listOf(sector, null, null), listOf(null, sector, null), sector,
            "+1.3", false, "+9.4", "+1.3", bestLap = sector,
        )
        val track = DriverTrack(longArrayOf(1000, 2000), floatArrayOf(2f, 4f), floatArrayOf(3f, 5f), 2)
        val laps = DriverLaps(longArrayOf(1000), listOf(snapshot), longArrayOf(1200), longArrayOf(1200), intArrayOf(6))
        val source = ReplayCore(
            "2026/Race/", "Race", mapOf("16" to DriverInfo("16", "LEC", 42)),
            mapOf("16" to track), mapOf("16" to StateLog(longArrayOf(1000), arrayOf(LapState.PUSH))),
            floatArrayOf(0f, 0f, 0f, 5f, 5f, 5f, 5f, 0f), true, 1000,
            mapOf("16" to laps), listOf(1000L to 1),
        )

        assertTrue(cache.write(source) {})
        val restored = requireNotNull(cache.read {})
        assertEquals(source.path, restored.path)
        assertArrayEquals(source.outline, restored.outline, 0f)
        assertEquals("LEC", restored.drivers.getValue("16").tla)
        assertEquals(3f to 4f, restored.tracks.getValue("16").at(1500))
        assertEquals(LapState.PUSH, restored.states.getValue("16").at(1500))
        val history = restored.laps.getValue("16")
        assertEquals(7, history.lapAt(1500))
        val card = requireNotNull(history.at(1500))
        assertEquals(300L, card.runningMs)
        assertEquals(4, card.position)
        assertEquals("+9.4", card.toLeader)
        assertEquals(sector.segments, card.bestLap?.segments)
        assertEquals(sector.previousTime, card.sectors.first()?.previousTime)
        assertEquals(source.parts, restored.parts)
        assertNull(ReplayCoreCache(temporary.root, 18).read {})

        temporary.root.resolve("replay-timing/17.bin").outputStream().use { it.write(1) }
        assertNull(cache.read {})
    }

    @Test
    fun correctingTheClockMovesAllReplayLookupsTogether() {
        val snapshot = DriverLaps.Snapshot(4, List(3) { null }, List(3) { null }, null, null, false, null, null)
        val history = DriverLaps(longArrayOf(1000), listOf(snapshot), longArrayOf(1200), longArrayOf(1200), intArrayOf(6))
        val shifted = history.shiftedBy(-500)
        assertNull(shifted.at(499))
        assertEquals(300L, shifted.at(1000)?.runningMs)
        assertEquals(7, shifted.lapAt(700))
        assertEquals(700L, shifted.lastCrossing(1000))
        val states = StateLog(longArrayOf(1000), arrayOf(LapState.OUT_LAP)).shiftedBy(-500)
        assertEquals(LapState.UNKNOWN, states.at(499))
        assertEquals(LapState.OUT_LAP, states.at(500))
    }
}
