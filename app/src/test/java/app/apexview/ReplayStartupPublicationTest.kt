package app.apexview

import org.junit.Assert.*
import org.junit.Test

class ReplayStartupPublicationTest {
    private val outline = floatArrayOf(0f, 0f, 8f, 0f, 8f, 8f, 0f, 8f)
    private val drivers = (1..22).associate { "$it" to DriverInfo("$it", "D$it", it) }
    private val tracks = drivers.mapValues { DriverTrack(longArrayOf(1000, 4000),
        floatArrayOf(0f, 1f), floatArrayOf(0f, 1f), 2) }
    private val rows = drivers.mapValues { (number, _) ->
        val row = DriverLaps.Snapshot(number.toInt(), List(3) { null }, List(3) { null },
            null, null, false, null, null)
        DriverLaps(longArrayOf(1000), listOf(row), longArrayOf())
    }

    private fun window(stage: ReplayTimingStage, positions: Map<String, DriverTrack> = tracks,
                       laps: Map<String, DriverLaps> = rows, path: String = "race/") =
        SessionTiming("Race", drivers, positions, emptyMap(), outline.copyOf(), false, emptyList(),
            1000, laps = laps, radioBase = path).apply {
            publicationStage = stage
            towerThroughUtc = 4000
            archivePartial = true
        }

    @Test fun savedRowsSurvivePositionOnlyPublicationsAtThePlayhead() {
        val owner = ReplayStartupPublication()
        val saved = window(ReplayTimingStage.SAVED_WINDOW)
        val first = owner.offer(saved, 1200)
        assertEquals(22, first.laps.size)
        val positions = window(ReplayTimingStage.TOPICS, laps = emptyMap())
        repeat(2) {
            val visible = owner.offer(positions, 2000)
            assertSame(first.geometry, visible.geometry)
            assertSame(rows, visible.laps)
            assertSame(tracks, visible.tracks)
        }
        assertSame(rows, owner.at(3000)!!.laps)
        val newRows = rows - "22"
        assertSame(newRows, owner.offer(window(ReplayTimingStage.TOPICS, laps = newRows), 3000).laps)
        val core = window(ReplayTimingStage.CORE)
        assertSame(core, owner.offer(core, 3500))
        assertNull(owner.at(3500))
    }

    @Test fun seekGapSessionSwitchAndAuthoritativeEmptyNeverBorrowOldRows() {
        val owner = ReplayStartupPublication()
        owner.offer(window(ReplayTimingStage.SAVED_WINDOW), 2000)
        assertTrue(owner.at(9000)!!.laps.isEmpty())
        assertEquals(22, owner.at(2000)!!.laps.size)
        val gap = window(ReplayTimingStage.TOPICS, emptyMap(), emptyMap()).apply {
            positionsIndexed = true
            towerIndexed = true
        }
        assertTrue(owner.offer(gap, 2000).tracks.isEmpty())
        assertTrue(owner.at(2000)!!.laps.isEmpty())
        val next = window(ReplayTimingStage.TOPICS, emptyMap(), emptyMap(), "other/")
        assertTrue(owner.offer(next, 2000).laps.isEmpty())
        assertSame(next.geometry, owner.at(2000)!!.geometry)
    }

    @Test fun compactArchivePreservesOldReadersAndEverySeekSample() {
        val builder = TrackBuilder()
        repeat(100) { builder.add(it.toLong(), it.toFloat(), 2f * it) }
        val prefix = builder.build()
        val finished = builder.finishArchive()
        assertEquals(100, finished.t.size)
        assertNotSame(prefix.t, finished.t)
        for (at in 0L until 100) assertEquals(prefix.at(at), finished.at(at))
    }
}
