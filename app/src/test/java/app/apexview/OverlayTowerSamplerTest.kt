package app.apexview

import org.junit.Assert.*
import org.junit.Test

class OverlayTowerSamplerTest {
    private val s1 = DriverLaps.Snapshot(
        position = 2, sectors = listOf(
            SectorCell("", Pace.NONE, listOf(2048, 2049, 0)),
            null, null
        ), lapSectors = emptyList(), lastLap = null, gap = "+1.000",
        inPit = false, toLeader = "+1.000", ahead = "+0.500"
    )
    private val s2 = DriverLaps.Snapshot(
        position = 1, sectors = listOf(
            SectorCell("28.900", Pace.PURPLE, listOf(2049, 2049, 2048)),
            SectorCell("", Pace.NONE, listOf(2048, 0, 0)), null
        ), lapSectors = emptyList(), lastLap = SectorCell("1:30.000", Pace.GREEN),
        gap = null, inPit = false, toLeader = null, ahead = null,
        bestLap = SectorCell("1:29.500", Pace.PURPLE)
    )

    private fun timing() = SessionTiming(
        type = "Race",
        drivers = mapOf("44" to DriverInfo("44", "HAM", 0xFF22CCAA.toInt())),
        tracks = emptyMap(),
        states = emptyMap(),
        outline = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f),
        hasStartFinish = true, corners = emptyList(), startUtcMs = 1_000L,
        laps = mapOf("44" to DriverLaps(
            longArrayOf(1_000L, 2_000L), listOf(s1, s2), longArrayOf()
        )),
        tyres = mapOf("44" to TyreLog(
            longArrayOf(1_000L, 3_000L), listOf(Tyre("SOFT", 5), Tyre("MEDIUM", 1))
        ))
    ).also {
        it.speeds = mapOf("44" to SpeedLog(
            longArrayOf(1_000L, 1_600L, 2_500L),
            shortArrayOf(260, 270, 288), 3, shortArrayOf(6, 7, 8)
        ))
    }

    @Test fun unchangedIntervalsShareSnapshotAndAllRowsByIdentity() {
        val sampler = OverlayTowerSampler(timing())
        val first = sampler.sample(1_100L)
        val same = sampler.sample(1_200L)
        assertSame("no new Compose snapshot for unchanged values", first, same)
        assertSame(first.rows.single().second, same.rows.single().second)
        assertSame(same, sampler.sample(1_200L))
    }

    @Test fun unchangedPollsReusePublicationButNeverSuppressNewTelemetryOrSeek() {
        val session = timing()
        val sampler = OverlayTowerSampler(session)
        val initial = sampler.sample(1_100L)
        for (clock in 1_101L..1_150L) assertSame(initial, sampler.sample(clock))
        val next = sampler.sample(1_700L)
        assertNotSame(initial, next)
        assertEquals(270, next.speeds.getValue("44").first)
        val restored = sampler.sample(1_100L)
        assertEquals(initial.rows, restored.rows)
        assertEquals(initial.speeds, restored.speeds)
        assertEquals(initial.normalizedSectors, restored.normalizedSectors)
    }

    @Test fun geometryOnlyUpdateKeepsRowsAndRetainsTheNewTelemetrySource() {
        val original = timing()
        val sampler = OverlayTowerSampler(original)
        val first = sampler.sample(1_100L)
        val next = SessionTiming(original.type, original.drivers, original.tracks, original.states,
            original.outline.copyOf(), original.hasStartFinish, original.corners, original.startUtcMs,
            laps = original.laps, tyres = original.tyres, weather = original.weather)
        next.speeds = mapOf("44" to SpeedLog(longArrayOf(1_000L), shortArrayOf(290), 1, shortArrayOf(8)))
        assertTrue(sampler.updateSource(next))
        val updated = sampler.sample(1_100L)
        assertSame(first.rows, updated.rows)
        assertEquals(290, updated.speeds.getValue("44").first)
        assertFalse(sampler.updateSource(timing()))
    }

    @Test fun speedGearLapTyreAndSectorChangesNeverGetSuppressed() {
        val sampler = OverlayTowerSampler(timing())
        val initial = sampler.sample(1_100L)
        val changedSpeed = sampler.sample(1_700L)
        assertNotSame(initial, changedSpeed)
        assertEquals(270, changedSpeed.speeds.getValue("44").first)
        assertEquals(7, changedSpeed.speeds.getValue("44").second)
        assertSame(initial.rows.single().second, changedSpeed.rows.single().second)
        val changedLap = sampler.sample(2_100L)
        assertEquals(1, changedLap.rows.single().second.position)
        assertEquals("28.900", changedLap.rows.single().second.sectors[0]?.time)
        assertEquals(listOf(2049, 2049, 2048),
            changedLap.rows.single().second.towerSector(0)?.segments)
        assertEquals(listOf(2048, 0, 0),
            changedLap.rows.single().second.towerSector(1)?.segments)
        assertEquals("1:29.500", changedLap.rows.single().second.bestLap?.time)
        val changedTyre = sampler.sample(3_100L)
        assertEquals("MEDIUM", changedTyre.tyres.getValue("44")?.compound)
        assertEquals(1, changedTyre.tyres.getValue("44")?.laps)
    }

    @Test fun lateSpeedDataWhileVideoIsPausedStillUpdatesTheTower() {
        val timing = timing()
        val sampler = OverlayTowerSampler(timing)
        val before = sampler.sample(1_100L)
        timing.speeds = mapOf("44" to SpeedLog(
            longArrayOf(1_000L), shortArrayOf(299), 1, shortArrayOf(8)
        ))
        val after = sampler.sample(1_100L)
        assertNotSame("same clock must not hide an updated timing topic", before, after)
        assertEquals(299, after.speeds.getValue("44").first)
        assertEquals(8, after.speeds.getValue("44").second)
    }

    @Test fun capturedSpeedSourcesAvoidFreezingLateDataAtTheSameVideoTime() {
        val session = timing()
        val sampler = OverlayTowerSampler(session)
        val initialSpeeds = session.speeds
        val first = sampler.sample(1_100L, initialSpeeds)
        val nextSpeeds = mapOf("44" to SpeedLog(
            longArrayOf(1_000L), shortArrayOf(312), 1, shortArrayOf(9)
        ))
        val changed = sampler.sample(1_100L, nextSpeeds)
        assertNotSame(first, changed)
        assertEquals(312, changed.speeds.getValue("44").first)
        assertEquals(9, changed.speeds.getValue("44").second)
        assertSame("unchanged subsequent worker polling must reuse the exact display state",
            changed, sampler.sample(1_100L, nextSpeeds))
    }

    @Test fun retainedTowerSamplesPreserveEveryLegacyVisibleFieldAcrossPlaybackAndSeek() {
        val session = timing()
        val sampler = OverlayTowerSampler(session)
        // Includes before-start, pitless racing, gear changes, lap/sector updates,
        // tyre changes and backward seek. There is no artificial data smoothing.
        for (now in listOf(500L, 1_100L, 1_700L, 2_100L, 3_100L,
            2_000L, 1_300L, 3_600L)) {
            val actual = sampler.sample(now)
            val reference = TowerSample(
                rows = session.laps.mapNotNull { (id, laps) ->
                    laps.at(now, includeRunningTime = false)?.let { id to it }
                }.sortedBy { it.second.position ?: 99 },
                header = towerSessionHeader(session, now),
                tyres = session.tyres.mapValues { it.value.at(now) },
                speeds = session.speeds.mapValues {
                    it.value.kmhAt(now) to it.value.gearAt(now)
                },
            )
            assertEquals("timing/sector row parity at $now", reference.rows, actual.rows)
            assertEquals(reference.rows.associate { (id, state) -> id to List(3, state::towerSector) },
                actual.normalizedSectors)
            assertEquals("flag/lap/countdown header parity at $now", reference.header, actual.header)
            assertEquals("gear/speed parity at $now", reference.speeds, actual.speeds)
            assertEquals("tyre/age parity at $now",
                reference.tyres.mapValues { it.value?.let { tyre -> tyre.compound to tyre.laps } },
                actual.tyres.mapValues { it.value?.let { tyre -> tyre.compound to tyre.laps } })
        }
    }

    @Test fun twentyFourDriverTowerRetainsOrderOnSeekWithoutChangingCachedSnapshots() {
        val snapshots = (1..24).associate { rank ->
            val number = rank.toString()
            number to DriverLaps(longArrayOf(1_000L, 2_000L),
                listOf(
                    DriverLaps.Snapshot(position = 25 - rank, sectors = emptyList(),
                        lapSectors = emptyList(), lastLap = null, gap = null, inPit = false,
                        toLeader = null, ahead = null),
                    DriverLaps.Snapshot(position = rank, sectors = emptyList(),
                        lapSectors = emptyList(), lastLap = null, gap = null, inPit = false,
                        toLeader = null, ahead = null)
                ), longArrayOf())
        }
        val t = SessionTiming(
            type = "Race", drivers = emptyMap(), tracks = emptyMap(),
            states = emptyMap(), outline = floatArrayOf(),
            hasStartFinish = false, corners = emptyList(), startUtcMs = 1_000L,
            laps = snapshots
        )
        val sampler = OverlayTowerSampler(t)
        val first = sampler.sample(1_100L)
        assertEquals((24 downTo 1).map { it.toString() }, first.rows.map { it.first })
        val later = sampler.sample(2_100L)
        assertEquals((1..24).map { it.toString() }, later.rows.map { it.first })
        val restored = sampler.sample(1_100L)
        assertEquals(first.rows, restored.rows)
        assertSame(restored, sampler.sample(1_100L))
    }

    @Test fun scratchReuseNeverMutatesPublishedMapsOrKeepsRemovedDrivers() {
        val session = timing()
        val sampler = OverlayTowerSampler(session)
        val initial = sampler.sample(1_100L)
        val later = sampler.sample(3_100L, emptyMap())
        assertTrue(later.speeds.isEmpty())
        assertEquals(260, initial.speeds.getValue("44").first)
        assertEquals("SOFT", initial.tyres.getValue("44")?.compound)
        assertTrue(initial.normalizedSectors.containsKey("44"))
        val beforeStart = sampler.sample(500L, emptyMap())
        assertTrue(beforeStart.rows.isEmpty())
        assertTrue(beforeStart.normalizedSectors.isEmpty())
        assertTrue(initial.normalizedSectors.containsKey("44"))
        val restored = sampler.sample(1_100L, session.speeds)
        assertEquals(initial.speeds, restored.speeds)
        assertEquals(initial.normalizedSectors, restored.normalizedSectors)
    }

    @Test fun backwardSeekRestoresOldLapAndOldTyres() {
        val sampler = OverlayTowerSampler(timing())
        sampler.sample(3_100L)
        val earlier = sampler.sample(1_200L)
        assertEquals(2, earlier.rows.single().second.position)
        assertEquals("SOFT", earlier.tyres.getValue("44")?.compound)
        assertEquals(260, earlier.speeds.getValue("44").first)
    }
}
