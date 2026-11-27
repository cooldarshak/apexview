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
                       laps: Map<String, DriverLaps> = rows, path: String = "race/", start: Long = 1000) =
        SessionTiming("Race", drivers, positions, emptyMap(), outline.copyOf(), false, emptyList(),
            start, laps = laps, radioBase = path).apply {
            publicationStage = stage
            towerThroughUtc = 4000
            archivePartial = true
        }

    private fun video(utc: Long) = ReplayPlayhead(utc - 1000, utc, 0)

    @Test fun savedRowsSurvivePositionOnlyPublicationsAtThePlayhead() {
        val owner = ReplayStartupPublication()
        val saved = window(ReplayTimingStage.SAVED_WINDOW)
        val first = owner.offer(saved, video(1200))
        assertEquals(22, first.laps.size)
        val positions = window(ReplayTimingStage.TOPICS, laps = emptyMap())
        repeat(2) {
            val visible = owner.offer(positions, video(2000))
            assertSame(first.geometry, visible.geometry)
            assertSame(rows, visible.laps)
            assertSame(tracks, visible.tracks)
        }
        assertSame(rows, owner.at(video(3000))!!.laps)
        val newRows = rows - "22"
        assertSame(newRows, owner.offer(window(ReplayTimingStage.TOPICS, laps = newRows), video(3000)).laps)
        val core = window(ReplayTimingStage.CORE)
        assertSame(core, owner.offer(core, video(3500)))
        assertNull(owner.at(video(3500)))
    }

    @Test fun coldTopicsDoNotBlinkBetweenIndependentCoveredPanels() {
        val owner = ReplayStartupPublication()
        owner.offer(window(ReplayTimingStage.TOPICS, positions = emptyMap()), video(1200))
        val both = owner.offer(window(ReplayTimingStage.TOPICS, laps = emptyMap()), video(2000))
        assertSame(rows, both.laps)
        assertSame(tracks, both.tracks)
        val empty = window(ReplayTimingStage.TOPICS, emptyMap(), emptyMap()).apply {
            positionsIndexed = true; towerIndexed = true
        }
        assertTrue(owner.offer(empty, video(2000)).laps.isEmpty())
        assertTrue(owner.at(video(2000))!!.tracks.isEmpty())
    }

    @Test fun seekGapSessionSwitchAndAuthoritativeEmptyNeverBorrowOldRows() {
        val owner = ReplayStartupPublication()
        owner.offer(window(ReplayTimingStage.SAVED_WINDOW), video(2000))
        assertTrue(owner.at(video(9000))!!.laps.isEmpty())
        assertEquals(22, owner.at(video(2000))!!.laps.size)
        val gap = window(ReplayTimingStage.TOPICS, emptyMap(), emptyMap()).apply {
            positionsIndexed = true
            towerIndexed = true
        }
        assertTrue(owner.offer(gap, video(2000)).tracks.isEmpty())
        assertTrue(owner.at(video(2000))!!.laps.isEmpty())
        val next = window(ReplayTimingStage.TOPICS, emptyMap(), emptyMap(), "other/")
        assertTrue(owner.offer(next, video(2000)).laps.isEmpty())
        assertSame(next.geometry, owner.at(video(2000))!!.geometry)
    }

    @Test fun provisionalClockKeepsCorrectedTowerAndAcceptsAbsolutePositions() {
        val owner = ReplayStartupPublication()
        val estimate = ReplayPlayhead(1_000L, null, 0L)
        val saved = window(ReplayTimingStage.SAVED_WINDOW)
        val first = owner.offer(saved, estimate)
        val newerPositions = tracks - "22"
        val provisional = window(ReplayTimingStage.TOPICS, newerPositions, rows - "22", start = 60_000L)
        val visible = owner.offer(provisional, estimate)
        assertEquals(saved.startUtcMs, visible.startUtcMs)
        assertSame(rows, visible.laps)
        assertSame(newerPositions, visible.tracks)
        assertSame(first.geometry, visible.geometry)
        assertTrue(owner.at(video(60_000L))!!.laps.isEmpty())
        val corrected = window(ReplayTimingStage.CORE, newerPositions, rows - "22")
        assertSame(corrected, owner.offer(corrected, estimate))
    }

    @Test fun temporaryPositionGapDoesNotDropClockCoveredStandings() {
        val owner = ReplayStartupPublication()
        val sparse = DriverTrack(longArrayOf(1_000L, 15_000L), floatArrayOf(1f, 2f), floatArrayOf(1f, 2f), 2)
        val visible = owner.offer(window(ReplayTimingStage.SAVED_WINDOW, mapOf("1" to sparse)), video(6_000L))
        assertTrue(visible.tracks.isEmpty())
        assertSame(rows, visible.laps)
        assertTrue(owner.at(video(16_000L))!!.laps.isEmpty())
    }

    @Test fun independentSavedTowerCoverageSurvivesMapEndButNeverBeyondTowerEnd() {
        val owner = ReplayStartupPublication()
        val saved = window(ReplayTimingStage.SAVED_WINDOW).apply {
            previewTowerFromUtc = 1000L
            towerThroughUtc = 9000L
        }
        val visible = owner.offer(saved, video(6000L))
        assertTrue(visible.tracks.isEmpty())
        assertSame(rows, visible.laps)
        assertEquals(9000L, visible.towerThroughUtc)
        assertEquals(1000L, visible.previewTowerFromUtc)
        assertTrue(owner.at(video(9001L))!!.laps.isEmpty())
        assertTrue(owner.at(video(999L))!!.laps.isEmpty())
        assertSame(rows, owner.at(video(8000L))!!.laps)
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

    @Test fun compactIncomingTowerDoesNotClaimCoverageFromItsOldPredecessor() {
        val owner = ReplayStartupPublication()
        val saved = window(ReplayTimingStage.SAVED_WINDOW).apply {
            previewTowerFromUtc = 1000L
            towerThroughUtc = 3000L
        }
        owner.offer(saved, video(1500L))
        val newer = window(ReplayTimingStage.TOPICS, laps = rows - "22").apply {
            previewTowerFromUtc = 2500L
            towerThroughUtc = 9000L
        }
        assertSame(newer.laps, owner.offer(newer, video(2600L)).laps)
        assertSame(saved.laps, owner.at(video(2000L))!!.laps)
        assertSame(newer.laps, owner.at(video(6000L))!!.laps)
        assertTrue(owner.at(video(9001L))!!.laps.isEmpty())
        assertTrue(owner.at(video(999L))!!.laps.isEmpty())
    }
}
