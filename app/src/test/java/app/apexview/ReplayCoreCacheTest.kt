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
            "+1.3", false, "+9.4", "+1.3", bestLap = sector, stopped = true,
        )
        val next = DriverLaps.Snapshot(
            4, snapshot.sectors, snapshot.lapSectors, sector,
            "+1.4", false, "+9.5", "+1.4", bestLap = sector,
        )
        val samples = 4097
        val track = DriverTrack(
            LongArray(samples) { (it + 1) * 1000L },
            FloatArray(samples) { it * 2f + 2f },
            FloatArray(samples) { it * 2f + 3f },
            samples,
        )
        val laps = DriverLaps(longArrayOf(1000, 2000), listOf(snapshot, next), longArrayOf(1200), longArrayOf(1200), intArrayOf(6))
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
        val restoredTrack = restored.tracks.getValue("16")
        assertEquals(3f to 4f, restoredTrack.at(1500))
        assertEquals(8193f to 8194f, restoredTrack.at(4_096_500))
        assertEquals(LapState.PUSH, restored.states.getValue("16").at(1500))
        val history = restored.laps.getValue("16")
        assertEquals(7, history.lapAt(1500))
        val card = requireNotNull(history.at(1500))
        assertEquals(300L, card.runningMs)
        assertEquals(4, card.position)
        assertEquals("+9.4", card.toLeader)
        assertTrue(card.stopped)
        val later = requireNotNull(history.at(2500))
        assertTrue(!later.stopped)
        org.junit.Assert.assertSame(card.sectors, later.sectors)
        org.junit.Assert.assertSame(card.lapSectors, later.lapSectors)
        assertEquals(sector.segments, card.bestLap?.segments)
        assertEquals(sector.previousTime, card.sectors.first()?.previousTime)
        assertEquals(source.parts, restored.parts)
        assertNull(ReplayCoreCache(temporary.root, 18).read {})

        val targetUtc = 2_000_500L
        val targetPosition = 2_000_000L
        val timing = SessionTiming(
            source.type, source.drivers, source.tracks, source.states, source.outline,
            source.hasStartFinish, emptyList(), source.startUtc, laps = source.laps,
            radioBase = LiveTimingArchive.BASE + source.path,
        )
        // An initial progressive archive already has genuine map/tower samples;
        // it must be restart-cacheable without waiting minutes for full history.
        timing.archivePartial = true
        assertTrue(cache.writePreview(timing, targetPosition, targetUtc) {})
        val preview = requireNotNull(cache.readPreview(targetPosition + 20_000L) {})
        assertEquals(track.at(targetUtc), preview.tracks.getValue("16").at(targetUtc))
        assertEquals(4, preview.laps.getValue("16").at(targetUtc)?.position)
        assertTrue(preview.tracks.getValue("16").n < track.n)
        assertNull(cache.readPreview(targetPosition + 46_000L) {})
        assertEquals(track.n, cache.read {}?.tracks?.get("16")?.n)

        val file = temporary.root.resolve("replay-timing/17.bin")
        java.io.RandomAccessFile(file, "rw").use { it.writeInt(2) }
        val reasons = mutableListOf<String>()
        assertNull(ReplayCoreCache(temporary.root, 17) { reasons.add(it) }.read {})
        assertTrue(reasons.single().contains("rebuild-version-2"))
        assertTrue(cache.write(source) {})
        try {
            cache.write(source) { throw java.util.concurrent.CancellationException() }
            throw AssertionError("Cancellation was swallowed")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(source.path, cache.read {}?.path)
        file.outputStream().use { it.write(1) }
        assertNull(cache.read {})
    }

    @Test
    fun reusesOnlyARecentPreviouslyObservedClock() {
        val saved = ReplayClockAnchor(baseMs = 1_791_719_700_480L, observedAtMs = 1_794_989_000_000L)
        val decoded = requireNotNull(ReplayClockAnchor.read(saved.encode(), saved.observedAtMs + 60_000))
        assertEquals(saved, decoded)
        val sample = ReplayPlayhead(12_000L, decoded.baseMs + 12_000L, -7_000L, cachedVideoClock = true)
        assertEquals(saved.baseMs + 5_000L, sample.utcAt(1))
        assertNull(ReplayClockAnchor.read(saved.encode(), saved.observedAtMs + 8 * 86_400_000L))
        assertNull(ReplayClockAnchor.read(saved.encode(), saved.observedAtMs - 1))
        assertNull(ReplayClockAnchor.read("0/${saved.observedAtMs}", saved.observedAtMs))
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
