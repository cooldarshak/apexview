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
    fun cacheRoundTripSharesIdenticalPositionTimesWithoutSharingCoordinates() {
        val cache = ReplayCoreCache(temporary.root, 93)
        val times = longArrayOf(1000, 2000, 3000)
        val first = DriverTrack(times, floatArrayOf(0f, 10f, 20f), floatArrayOf(0f, 4f, 8f), 3)
        val second = DriverTrack(times.copyOf(), floatArrayOf(10f, 20f, 30f), floatArrayOf(4f, 8f, 12f), 3)
        val different = DriverTrack(longArrayOf(1000, 2000, 3001), first.x.copyOf(), first.y.copyOf(), 3)
        val snapshot = DriverLaps.Snapshot(1, listOf(null, null, null), listOf(null, null, null),
            null, null, false, null, null)
        val laps = DriverLaps(longArrayOf(1000), listOf(snapshot), longArrayOf(), longArrayOf(), intArrayOf())
        val source = ReplayCore("2026/Race/", "Race",
            mapOf("1" to DriverInfo("1", "VER", 42), "2" to DriverInfo("2", "TWO", 43),
                "3" to DriverInfo("3", "THR", 44)),
            linkedMapOf("1" to first, "2" to second, "3" to different), emptyMap(),
            floatArrayOf(0f, 0f, 0f, 5f, 5f, 5f, 5f, 0f), true, 1000,
            mapOf("1" to laps), emptyList())
        assertTrue(cache.write(source) {})
        val tracks = requireNotNull(cache.read {}).tracks
        val a = tracks.getValue("1")
        val b = tracks.getValue("2")
        val c = tracks.getValue("3")
        assertTrue(a.t === b.t)
        assertTrue(a.t !== c.t)
        assertTrue(a.x !== b.x)
        assertEquals(first.at(1500), a.at(1500))
        assertEquals(second.at(1500), b.at(1500))
        assertEquals(different.endMs, c.endMs)
        assertTrue(a.t !== requireNotNull(cache.read {}).tracks.getValue("1").t)
    }

    @Test
    fun preservesIndexedReplayAndRejectsIncompleteFiles() {
        val cache = ReplayCoreCache(temporary.root, 17)
        val sector = SectorCell("28.002", Pace.GREEN, listOf(2049, 2051), "28.800")
        val snapshot = DriverLaps.Snapshot(
            4, listOf(sector, null, null), listOf(null, sector, null), sector,
            "+1.3", false, "+9.4", "+1.3", bestLap = sector, stopped = true,
        )
        val next = DriverLaps.Snapshot(
            3, snapshot.sectors, snapshot.lapSectors, sector,
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
        timing.towerThroughUtc = targetUtc - 1L
        // Stale rank coverage must not block the valid position checkpoint,
        // nor may it be persisted as an apparently ready tower.
        assertTrue(cache.writePreview(timing, targetPosition, targetUtc) {})
        val mapOnly = requireNotNull(cache.readPreview(targetPosition) {})
        assertTrue(mapOnly.laps.isEmpty())
        assertEquals(track.at(targetUtc), mapOnly.tracks.getValue("16").at(targetUtc))
        assertEquals(Long.MIN_VALUE, mapOnly.previewTowerThroughUtc)
        timing.towerThroughUtc = targetUtc + 30_000L
        assertTrue(cache.writePreview(timing, targetPosition, targetUtc) {})
        val preview = requireNotNull(cache.readPreview(targetPosition + 20_000L) {})
        assertEquals(targetUtc - 90_000L, preview.previewTowerFromUtc)
        assertEquals(targetUtc + 30_000L, preview.previewTowerThroughUtc)
        assertEquals(track.at(targetUtc), preview.tracks.getValue("16").at(targetUtc))
        assertEquals(3, preview.laps.getValue("16").at(targetUtc)?.position)
        assertEquals(1, preview.laps.getValue("16").at(targetUtc)?.positionChange)
        assertTrue(preview.tracks.getValue("16").n < track.n)
        assertEquals(laps.lapAt(targetUtc),preview.laps.getValue("16").lapAt(targetUtc))
        assertEquals(laps.lastCrossing(targetUtc),preview.laps.getValue("16").lastCrossing(targetUtc))
        assertNull(cache.readPreview(targetPosition + 46_000L) {})
        assertEquals(track.n, cache.read {}?.tracks?.get("16")?.n)
        // Full V3 cache remains readable; V2 is still rejected below.
        assertNull(cache.read {}?.previewTowerThroughUtc)
        // V4 has the same independent coverage but no original rank field.
        // One rank entry: count(4), modified-UTF length(2), "16"(2), rank(4).
        val previewFile = temporary.root.resolve("replay-previews/17.bin")
        java.io.RandomAccessFile(previewFile, "rw").use { it.writeInt(4); it.setLength(it.length() - 12L) }
        assertEquals(targetUtc + 30_000L, requireNotNull(cache.readPreview(targetPosition) {}).previewTowerThroughUtc)
        // Old V3 previews remain readable and use conservative legacy coverage.
        java.io.RandomAccessFile(previewFile, "rw").use { it.writeInt(3); it.setLength(it.length() - 16L) }
        assertNull(requireNotNull(cache.readPreview(targetPosition) {}).previewTowerThroughUtc)

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
    fun towerOnlyCheckpointDoesNotWaitForCircuitGeometryOrPositions() {
        val cache = ReplayCoreCache(temporary.root, 28)
        val snapshot = DriverLaps.Snapshot(4, List(3) { null }, List(3) { null },
            null, null, false, null, null)
        val timing = SessionTiming("Race", mapOf("16" to DriverInfo("16","LEC",42)),
            emptyMap(), emptyMap(), FloatArray(0), false, emptyList(), 1000L,
            laps = mapOf("16" to DriverLaps(longArrayOf(1000), listOf(snapshot), longArrayOf())),
            radioBase = LiveTimingArchive.BASE + "2026/Race/")
        timing.archivePartial = true
        timing.previewTowerFromUtc = 1000L
        timing.towerThroughUtc = 3000L
        assertEquals(ReplayPreviewCoverage.TOWER, ReplayPreviewCoverage.at(timing, 2000L))
        assertEquals(0, ReplayPreviewCoverage.at(timing, 4000L))
        assertTrue(cache.writePreview(timing, 1000L, 2000L) {})
        val restored = requireNotNull(cache.readPreview(1000L) {})
        assertTrue(restored.tracks.isEmpty())
        assertTrue(restored.outline.isEmpty())
        assertEquals(4, restored.laps.getValue("16").at(2000L)?.position)
        assertEquals(1000L, restored.previewTowerFromUtc)
        assertEquals(3000L, restored.previewTowerThroughUtc)
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
