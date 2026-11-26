package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TimingData shaped like F1's: a list-style snapshot first, then index-keyed updates. */
class DriverTimingTest {
    private val t0 = 1_000_000L

    private val stream = listOf(
        "00:00:01.000{\"Lines\":{\"16\":{\"Position\":\"3\",\"InPit\":true,\"NumberOfLaps\":0,\"Sectors\":[{\"Value\":\"\"},{\"Value\":\"\"},{\"Value\":\"\"}]}}}",
        "00:01:00.000{\"Lines\":{\"16\":{\"InPit\":false,\"PitOut\":true}}}",
        "00:02:00.000{\"Lines\":{\"16\":{\"NumberOfLaps\":1}}}",
        "00:02:30.000{\"Lines\":{\"16\":{\"Sectors\":{\"0\":{\"Value\":\"30.123\",\"PersonalFastest\":true}}}}}",
        "00:03:00.000{\"Lines\":{\"16\":{\"Sectors\":{\"1\":{\"Value\":\"28.900\",\"OverallFastest\":true,\"PersonalFastest\":true}}}}}",
        "00:03:20.000{\"Lines\":{\"16\":{\"Sectors\":{\"2\":{\"Value\":\"22.001\"}},\"NumberOfLaps\":2,\"LastLapTime\":{\"Value\":\"1:21.024\",\"PersonalFastest\":true},\"TimeDiffToFastest\":\"+0.289\",\"Position\":\"2\"}}}",
        "00:03:21.000{\"Lines\":{\"16\":{\"Sectors\":{\"0\":{\"Value\":\"\"},\"1\":{\"Value\":\"\"},\"2\":{\"Value\":\"\"}}}}}",
    ).joinToString("\r\n")

    private val laps = DriverTimingParser.parse(stream, t0).getValue("16")

    @Test
    fun sectorsFillInWithTheirColours() {
        val s = laps.at(t0 + 185_000)!!
        assertEquals("30.123", s.sectors[0]!!.time)
        assertEquals(Pace.GREEN, s.sectors[0]!!.pace)
        assertEquals(Pace.PURPLE, s.sectors[1]!!.pace)
        assertNull(s.sectors[2])
        assertEquals(3, s.position)
    }

    @Test
    fun runningTimeCountsFromTheLastCrossing() {
        val s = laps.at(t0 + 150_000)!!
        assertEquals(30_000L, s.runningMs)
        assertNull("in the pits at the start", laps.at(t0 + 30_000)!!.runningMs)
    }

    @Test
    fun lapCompleteBringsLapTimeGapAndPosition() {
        val s = laps.at(t0 + 200_500)!!
        assertEquals("1:21.024", s.lastLap!!.time)
        assertEquals(Pace.GREEN, s.lastLap!!.pace)
        assertEquals("+0.289", s.gap)
        assertEquals(2, s.position)
        assertEquals(1, s.positionChange)
        assertEquals(500L, s.runningMs)
        // The finished lap's sectors move aside; the new lap starts empty.
        assertEquals(Pace.YELLOW, s.lapSectors[2]!!.pace)
        assertEquals(Pace.PURPLE, s.lapSectors[1]!!.pace)
        assertTrue(s.sectors.all { it == null })
    }

    @Test
    fun bestLapPreviousSectorAndMicroSegmentsAreMergedFromTimingF1() {
        val base = listOf(
            "00:00:01.000{\"Lines\":{\"16\":{\"Position\":\"1\",\"Sectors\":[{\"Value\":\"\"},{\"Value\":\"\"},{\"Value\":\"\"}]}}}",
            "00:01:00.000{\"Lines\":{\"16\":{\"Sectors\":{\"0\":{\"Value\":\"27.506\",\"PersonalFastest\":true}}}}}",
        ).joinToString("\n")
        val micro = listOf(
            "00:01:01.000{\"Lines\":{\"16\":{\"BestLapTime\":{\"Value\":\"1:33.047\",\"OverallFastest\":true},\"Sectors\":{\"0\":{\"PreviousValue\":\"27.600\",\"Segments\":[{\"Status\":2051},{\"Status\":2049},{\"Status\":2048},{\"Status\":2064}]}}}}}",
        ).joinToString("\n")
        val state = DriverTimingParser.parse(base, t0, micro).getValue("16").at(t0 + 61_000)!!
        assertEquals("1:33.047", state.bestLap!!.time)
        assertEquals(Pace.PURPLE, state.bestLap!!.pace)
        assertEquals("27.600", state.sectors[0]!!.previousTime)
        assertEquals(listOf(2051, 2049, 2048, 2064), state.sectors[0]!!.segments)
    }

    @Test
    fun readsLatestQualifyingPartGapsAndBestLap() {
        val qualifying = """00:00:01.000{"Lines":{"16":{"Stats":{"0":{"TimeDiffToFastest":"+0.120","TimeDifftoPositionAhead":"+0.034"}},"BestLapTimes":{"0":{"Value":"1:34.567","PersonalFastest":true}}}}}"""
        val state = DriverTimingParser.parse(qualifying, t0).getValue("16").at(t0 + 2_000)!!
        assertEquals("+0.120", state.toLeader)
        assertEquals("+0.034", state.ahead)
        assertEquals("1:34.567", state.bestLap!!.time)
        assertEquals(Pace.GREEN, state.bestLap!!.pace)
    }

    @Test
    fun readsRaceGapFromNestedGapToLeaderValue() {
        val race = """00:00:01.000{"Lines":{"1":{"Position":"1","GapToLeader":{"Value":"LEADER"},"IntervalToPositionAhead":{"Value":"0.000"}},"4":{"Position":"2","GapToLeader":{"Value":"+1.234"},"IntervalToPositionAhead":{"Value":"+1.234"}}}}"""
        val parsed = DriverTimingParser.parse(race, t0)
        val second = parsed.getValue("4").at(t0 + 2_000)!!
        assertEquals("+1.234", second.toLeader)
        assertEquals("+1.234", second.ahead)
    }

    @Test
    fun retiredStatusStaysLatchedWhenLaterDeltasClearTheFlag() {
        val stream = listOf(
            "00:00:01.000{\"Lines\":{\"4\":{\"Position\":\"12\",\"Retired\":true}}}",
            "00:00:02.000{\"Lines\":{\"4\":{\"Retired\":false,\"Stopped\":false}}}",
        ).joinToString("\n")
        val state = DriverTimingParser.parse(stream, t0).getValue("4").at(t0 + 3_000)!!
        assertTrue(state.retired)
    }

    @Test
    fun stopIsTemporaryAndRetirementClearsOnNewLap() {
        val stream = listOf(
            """00:00:01.000{"Lines":{"4":{"Position":"12","NumberOfLaps":3,"Stopped":true}}}""",
            """00:00:02.000{"Lines":{"4":{"Stopped":false,"Retired":true}}}""",
            """00:00:03.000{"Lines":{"4":{"Retired":false}}}""",
            """00:00:04.000{"Lines":{"4":{"NumberOfLaps":4}}}""",
        ).joinToString("\n")
        val laps = DriverTimingParser.parse(stream, t0).getValue("4")
        assertTrue(laps.at(t0 + 1_500)!!.stopped)
        assertTrue(!laps.at(t0 + 1_500)!!.retired)
        assertTrue(laps.at(t0 + 3_500)!!.retired)
        assertTrue(!laps.at(t0 + 4_500)!!.retired)
        assertTrue(!laps.at(t0 + 4_500)!!.stopped)
    }

    @Test
    fun aLateFinalSectorStillCountsForTheLapItFinished() {
        val late = listOf(
            "00:00:01.000{\"Lines\":{\"4\":{\"NumberOfLaps\":3}}}",
            "00:01:00.000{\"Lines\":{\"4\":{\"NumberOfLaps\":4,\"LastLapTime\":{\"Value\":\"1:33.100\"}}}}",
            "00:01:01.000{\"Lines\":{\"4\":{\"Sectors\":{\"2\":{\"Value\":\"30.100\",\"OverallFastest\":true}}}}}",
        ).joinToString("\n")
        val withMicro = late.replace(
            "\"Value\":\"30.100\",\"OverallFastest\":true",
            "\"Value\":\"30.100\",\"OverallFastest\":true,\"Segments\":[{\"Status\":2051},{\"Status\":2049}]",
        )
        val s = DriverTimingParser.parse(withMicro, t0).getValue("4").at(t0 + 62_000)!!
        assertEquals(Pace.PURPLE, s.lapSectors[2]!!.pace)
        assertEquals(listOf(2051, 2049), s.lapSectors[2]!!.segments)
        assertNull(s.sectors[2])
    }

    @Test
    fun lapNumbersComeFromF1sCount() {
        assertNull("nothing known before the first count", laps.lapAt(t0))
        assertEquals(1, laps.lapAt(t0 + 60_000))
        assertEquals(2, laps.lapAt(t0 + 150_000))
        assertEquals(3, laps.lapAt(t0 + 202_000))
        // Joined mid-race: the first count seen is already 14.
        val joined = DriverTimingParser.parse("00:00:01.000{\"Lines\":{\"1\":{\"NumberOfLaps\":14}}}", t0).getValue("1")
        assertEquals(15, joined.lapAt(t0 + 5_000))
    }

    @Test
    fun keepsSparseMiniSectorSlotsAndShowsOnlyThisLapsProgress() {
        val primary = listOf(
            """00:00:01.000{"Lines":{"16":{"Position":"1","NumberOfLaps":0}}}""",
            """00:01:12.000{"Lines":{"16":{"NumberOfLaps":1,"Sectors":{"2":{"Value":"23.021"}}}}}""",
            """00:01:12.600{"Lines":{"16":{"NumberOfLaps":0,"Sectors":{"2":{"Value":"23.021"}}}}}""",
        ).joinToString("\n")
        val supplement = listOf(
            """00:00:10.000{"Lines":{"16":{"Sectors":{"0":{"Segments":{"2":{"Status":2049}}}}}}}""",
            """00:00:11.000{"Lines":{"16":{"Sectors":{"0":{"Value":"","Segments":{"0":{"Status":2048}}}}}}}""",
            """00:00:35.000{"Lines":{"16":{"Sectors":{"1":{"Segments":{"1":{"Status":2049}}}}}}}""",
            """00:01:00.000{"Lines":{"16":{"Sectors":{"2":{"Segments":{"0":{"Status":2048}}}}}}}""",
            """00:01:12.300{"Lines":{"16":{"Sectors":{"0":{"Segments":{"0":{"Status":2048}}}}}}}""",
            """00:01:13.000{"Lines":{"16":{"Sectors":{"2":{"Segments":{"2":{"Status":2051}}}}}}}""",
            """00:01:14.000{"Lines":{"16":{"Sectors":{"1":{"Segments":{"0":{"Status":2048}}}}}}}""",
        ).joinToString("\n")
        val laps = DriverTimingParser.parse(primary, t0, supplement).getValue("16")
        assertEquals(listOf(0, 0, 2049), laps.at(t0 + 10_000)!!.towerSector(0)!!.segments)
        assertEquals(listOf(2048, 0, 2049), laps.at(t0 + 11_000)!!.towerSector(0)!!.segments)
        assertNull(laps.at(t0 + 11_000)!!.towerSector(1))
        assertEquals(listOf(0, 2049), laps.at(t0 + 35_000)!!.towerSector(1)!!.segments)
        assertNull(laps.at(t0 + 35_000)!!.towerSector(2))
        val newLap = laps.at(t0 + 73_000)!!
        assertEquals(2, laps.lapAt(t0 + 73_000))
        assertEquals(listOf(2048, 0, 0), newLap.towerSector(0)!!.segments)
        assertEquals(listOf(0, 0), newLap.towerSector(1)!!.segments)
        assertEquals(listOf(0, 0, 0), newLap.towerSector(2)!!.segments)
        assertEquals("", newLap.towerSector(2)!!.time)
        assertEquals(listOf(2048, 0, 2051), newLap.lapSectors[2]!!.segments)
        assertEquals(listOf(2048, 0), laps.at(t0 + 74_000)!!.towerSector(1)!!.segments)
    }

    @Test
    fun newLapClearsTheSectors() {
        val s = laps.at(t0 + 202_000)!!
        assertTrue(s.sectors.all { it == null })
        assertEquals("1:21.024", s.lastLap!!.time)
    }
    @Test
    fun timingTopicsAreMergedByTimestampRatherThanFileOrder() {
        val primary = listOf(
            """00:00:01.000{"Lines":{"16":{"Position":"1"}}}""",
            """00:00:03.000{"Lines":{"16":{"Position":"3"}}}""",
        ).joinToString("\n")
        val supplement = listOf(
            """00:00:02.000{"Lines":{"16":{"Position":"2"}}}""",
            """00:00:04.000{"Lines":{"16":{"Position":"4"}}}""",
        ).joinToString("\n")

        val laps = DriverTimingParser.parse(primary, t0, supplement).getValue("16")
        assertEquals(2, laps.at(t0 + 2_500)!!.position)
        assertEquals(4, laps.at(t0 + 4_500)!!.position)
    }

    @Test
    fun outOfOrderTopicFallsBackToChronologicalSorting() {
        val primary = listOf(
            """00:00:03.000{"Lines":{"16":{"Position":"3"}}}""",
            """00:00:01.000{"Lines":{"16":{"Position":"1"}}}""",
        ).joinToString("\n")
        val supplement = """00:00:02.000{"Lines":{"16":{"Position":"2"}}}"""

        val laps = DriverTimingParser.parse(primary, t0, supplement).getValue("16")
        assertEquals(1, laps.at(t0 + 1_500)!!.position)
        assertEquals(2, laps.at(t0 + 2_500)!!.position)
        assertEquals(3, laps.at(t0 + 3_500)!!.position)
    }
}

class DriverTimingSnapshotOwnershipTest {
    @Test fun gapOnlyChangesShareSectorStorageWithoutMutatingHistory() {
        val stream = listOf(
            """00:00:01.000{"Lines":{"4":{"NumberOfLaps":1,"Sectors":{"0":{"Value":"30.123"}}}}}""",
            """00:00:02.000{"Lines":{"4":{"TimeDiffToFastest":"+0.120"}}}""",
            """00:00:03.000{"Lines":{"4":{"Sectors":{"1":{"Value":"29.456"}}}}}"""
        ).joinToString("\n")
        val laps = DriverTimingParser.parse(stream, 0).getValue("4")
        val first = laps.at(1500)!!
        val second = laps.at(2500)!!
        val third = laps.at(3500)!!
        org.junit.Assert.assertSame(first.sectors, second.sectors)
        org.junit.Assert.assertSame(first.lapSectors, second.lapSectors)
        org.junit.Assert.assertNotSame(second.sectors, third.sectors)
        org.junit.Assert.assertNull(first.sectors[1])
        org.junit.Assert.assertEquals("29.456", third.sectors[1]?.time)
    }
}
