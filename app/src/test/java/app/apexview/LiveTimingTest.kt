package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Feeds the archive parser data shaped like F1's files (per FastF1's parser): jsonStream lines are
 * "HH:MM:SS.mmm" + payload, and Position.z payloads are quoted base64 raw-deflate JSON with
 * 7-digit fractional timestamps.
 */
class LiveTimingTest {
    private val archive = LiveTimingArchive("token", "test")
    private val t0 = Instant.parse("2026-10-24T20:00:00Z").toEpochMilli()

    @Test
    fun cachedSeasonIndexNeedsTheExactPublishedSession() {
        val index = org.json.JSONObject(
            """{"Meetings":[{"Sessions":[{"Key":42,"Type":"Race","Path":"2026/Race/"},{"Key":43,"Type":"Practice"}]}]}"""
        )
        assertEquals("2026/Race/" to "Race", archive.exactArchiveSession(42, index))
        assertNull(archive.exactArchiveSession(43, index))
        assertNull(archive.exactArchiveSession(44, index))
    }

    @Test
    fun positionPreviewFollowsACorrectedArchiveClock() {
        val window = ReplayPositionWindow(4_781_048L)
        assertEquals(4_901_048L, window.lastMs)
        assertEquals(false, window.retarget(4_782_000L))
        assertTrue(window.retarget(7_644_032L))
        assertEquals(7_554_032L, window.firstMs)
        assertEquals(7_764_032L, window.lastMs)
        assertTrue(window.retarget(5_000L))
        assertEquals(0L, window.firstMs)
    }

    @Test
    fun leavingThePitsWithoutPitOutStillCountsAsOut() {
        val stream = listOf(
            "00:00:01.000{\"Lines\":{\"4\":{\"InPit\":true,\"NumberOfLaps\":0}}}",
            "00:05:00.000{\"Lines\":{\"4\":{\"InPit\":false}}}",
        ).joinToString("\n")
        val states = archive.parseStates(stream, t0).states.getValue("4")
        assertEquals(LapState.PIT, states.at(t0 + 60_000))
        assertEquals(LapState.OUT_LAP, states.at(t0 + 400_000))
    }

    private fun stamp(offsetMs: Long): String {
        val s = offsetMs / 1000
        return "%02d:%02d:%02d.%03d".format(s / 3600, s / 60 % 60, s % 60, offsetMs % 1000)
    }

    private fun deflate(text: String): String {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(text.toByteArray())
        deflater.finish()
        val buffer = ByteArray(64 * 1024)
        val n = deflater.deflate(buffer)
        deflater.end()
        return Base64.getEncoder().encodeToString(buffer.copyOf(n))
    }

    /** Car [number] lapping a circle of radius 10000 (1 km) every [lapMs], sampled every 220 ms. */
    private fun circle(number: String, lapMs: Long, atMs: Long): String {
        val a = 2 * PI * (atMs % lapMs) / lapMs
        val x = 10_000 * cos(a)
        val y = 10_000 * sin(a)
        return "\"$number\":{\"Status\":\"OnTrack\",\"X\":${x.toInt()},\"Y\":${y.toInt()},\"Z\":0}"
    }

    private fun positionStream(): String {
        val lines = StringBuilder()
        var at = 0L
        while (at < 400_000) {
            // Two samples per line, like the real feed batches them.
            val samples = listOf(at, at + 220).joinToString(",") { ms ->
                val ts = Instant.ofEpochMilli(t0 + ms).toString().replace("Z", "0000Z").let {
                    if (it.contains('.')) it else it.replace("0000Z", ".0000000Z")
                }
                val garage = "\"99\":{\"Status\":\"OffTrack\",\"X\":5,\"Y\":5,\"Z\":0}"
                "{\"Timestamp\":\"$ts\",\"Entries\":{${circle("44", 80_000, ms)},${circle("1", 90_000, ms)},$garage}}"
            }
            lines.append(stamp(at)).append('"').append(deflate("{\"Position\":[$samples]}")).append("\"\r\n")
            at += 440
        }
        return lines.toString()
    }

    @Test
    fun positionsDecodeAndInterpolate() {
        val tracks = archive.parsePositions(positionStream())
        assertEquals(setOf("44", "1"), tracks.keys) // OffTrack car 99 is dropped

        val track = tracks.getValue("44").build()
        val p = assertNotNullAndGet(track.at(t0 + 20_000)) // a quarter lap in: (0, 10000)
        assertTrue("x was ${p.first}", kotlin.math.abs(p.first) < 300)
        assertTrue("y was ${p.second}", kotlin.math.abs(p.second - 10_000) < 300)
        assertNull(track.at(t0 - 1_000))
    }

    @Test
    fun outlineIsOneClosedLapOfTheFastestCar() {
        val tracks = archive.parsePositions(positionStream())
        val outline = archive.traceOutline(tracks.values.toList())
        val points = outline.size / 2
        // Car 44 laps in 80 s at 220 ms per sample: about 364 points.
        assertTrue("points: $points", points in 340..380)
        val gap = hypot(outline[0] - outline[outline.size - 2], outline[1] - outline[outline.size - 1])
        assertTrue("loop gap $gap", gap < 300)
    }

    @Test
    fun positionParserReportsMalformedRecordsWithoutDiscardingValidCars() {
        val report = archive.parsePositionsDetailed(positionStream() + "00:00:00.000\"not-base64!\"\n")

        assertTrue(report.inputRecords > report.decodedRecords)
        assertTrue(report.decodedRecords > 0)
        assertEquals(1, report.malformedRecords)
        assertTrue(report.positionSamples > 0)
        assertEquals(setOf("44", "1"), report.tracks.keys)
    }

    @Test
    fun heartbeatGivesStreamStart() {
        val heartbeat = stamp(5_000) + "{\"Utc\":\"2026-10-24T20:00:05.0000000Z\",\"_kf\":true}\r\n"
        assertEquals(t0, archive.parseStreamStart(heartbeat))
    }

    @Test
    fun aStreamPastADayKeepsItsLines() {
        // F1's recorder can run across a weekend; past a day its offsets gain a "d." in front.
        val radio = listOf(
            "23:59:59.000{\"Captures\":[{\"Utc\":\"2026-11-01T19:55:24.566Z\",\"RacingNumber\":\"50\",\"Path\":\"TeamRadio/A.mp3\"}]}",
            "1.02:36:53.637{\"Captures\":{\"1\":{\"Utc\":\"2026-11-01T19:59:28.007Z\",\"RacingNumber\":\"1\",\"Path\":\"TeamRadio/B.mp3\"}}}",
        ).joinToString("\r\n")
        assertEquals(listOf("TeamRadio/A.mp3", "TeamRadio/B.mp3"), archive.parseRadio(radio).map { it.path })
        assertEquals(((24 + 2) * 3600 + 36 * 60 + 53) * 1000L + 637, streamOffsetMs("1.02:36:53.637{}"))
        assertEquals(5_000L, streamOffsetMs("﻿00:00:05.000{}"))
    }

    @Test
    fun oneOddBeatDoesntMoveTheStreamStart() {
        val heartbeat = listOf(
            stamp(5_000) + "{\"Utc\":\"2026-10-26T05:00:05.0000000Z\"}",
            stamp(10_000) + "{\"Utc\":\"2026-10-24T20:00:10.0000000Z\"}",
            stamp(15_000) + "{\"Utc\":\"2026-10-24T20:00:15.0000000Z\"}",
        ).joinToString("\r\n")
        assertEquals(t0, archive.parseStreamStart(heartbeat))
    }

    @Test
    fun theCarsOwnTimesGiveTheStreamStartToo() {
        // Each line's newer sample is 220 ms after its offset.
        val start = assertNotNullAndGet(archive.positionsStreamStart(positionStream()))
        assertEquals(t0 + 220, start)
    }

    @Test
    fun lapStatesFollowPitAndLapCount() {
        val timing = listOf(
            stamp(1_000) + "{\"Lines\":{\"44\":{\"InPit\":true,\"NumberOfLaps\":0}},\"_kf\":true}",
            stamp(60_000) + "{\"Lines\":{\"44\":{\"InPit\":false,\"PitOut\":true}}}",
            stamp(120_000) + "{\"Lines\":{\"44\":{\"NumberOfLaps\":1}}}",
            stamp(210_000) + "{\"Lines\":{\"44\":{\"NumberOfLaps\":2}}}",
            stamp(300_000) + "{\"Lines\":{\"44\":{\"InPit\":true}}}",
        ).joinToString("\r\n")
        val parsed = archive.parseStates(timing, t0)
        val log = parsed.states.getValue("44")
        assertEquals(LapState.PIT, log.at(t0 + 30_000))
        assertEquals(LapState.OUT_LAP, log.at(t0 + 90_000))
        assertEquals(LapState.PUSH, log.at(t0 + 150_000))
        assertEquals(LapState.COOL_DOWN, log.at(t0 + 250_000))
        assertEquals(LapState.PIT, log.at(t0 + 310_000))
        assertEquals(listOf("44" to t0 + 120_000, "44" to t0 + 210_000), parsed.crossings)
    }

    @Test
    fun startFinishIsWhereLapsTickOver() {
        val tracks = archive.parsePositions(positionStream())
        val outline = archive.traceOutline(tracks.values.toList())
        val built = tracks.mapValues { it.value.build() }
        // Both cars cross at angle 0, the point (10000, 0): car 44 every 80 s, car 1 every 90 s.
        val crossings = (1..4).map { "44" to t0 + it * 80_000L } + (1..4).map { "1" to t0 + it * 90_000L }
        val index = archive.findStartFinish(outline, crossings, built)!!
        val x = outline[index * 2]
        val y = outline[index * 2 + 1]
        assertTrue("line at ($x, $y)", hypot(x - 10_000, y) < 400)
    }

    @Test
    fun progressGoesRoundTheLapSmoothly() {
        val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)
        val g = TrackGeometry(square)
        assertEquals(400f, g.length, 0.01f)
        assertEquals(0.125f, g.progress(50f, -5f), 0.01f)  // halfway along the first side
        assertEquals(0.375f, g.progress(105f, 50f), 0.01f)
        assertEquals(0.875f, g.progress(-3f, 50f), 0.01f)  // closing side, back to the start
    }

    @Test
    fun mapFitKeepsTrackOnScreen() {
        val outline = archive.traceOutline(archive.parsePositions(positionStream()).values.toList())
        val fit = MapFit.of(outline, 2000f, 1000f, 50f)!!
        for (i in outline.indices step 2) {
            val p = fit.map(outline[i], outline[i + 1])
            assertTrue("x ${p.x}", p.x in 49f..1951f)
            assertTrue("y ${p.y}", p.y in 49f..951f)
        }
    }

    @Test
    fun archiveLinesGoInFrontOfALiveSessionCountedFromItsStart() {
        val archive = "\uFEFF00:00:01.000{\"a\":1}\n00:00:09.000{\"c\":3}\n1.00:00:02.000{\"b\":2}\n"
        // The archive counts from 10 000; the live session from 5 000 and joined at 15 000.
        val rebased = rebaseStream(archive, 10_000L, 5_000L, 15_000L)
        assertEquals("00:00:06.000{\"a\":1}\n", rebased)
        val dayLater = rebaseStream(archive, 10_000L, 5_000L, 10_000L + 86_400_000L + 5_000L)
        assertEquals("00:00:06.000{\"a\":1}\n00:00:14.000{\"c\":3}\n24:00:07.000{\"b\":2}\n", dayLater)
    }

    @Test
    fun aLiveCarsTrackStartsWithTheArchivesSamplesFromBeforeIt() {
        val past = TrackBuilder().apply { add(1_000L, 1f, 1f); add(2_000L, 2f, 2f); add(3_000L, 3f, 3f) }
        val live = TrackBuilder().apply { add(3_000L, 30f, 30f); add(4_000L, 40f, 40f) }
        val track = live.build(past)
        assertEquals(1_000L, track.startMs)
        assertEquals(4_000L, track.endMs)
        assertEquals(1.5f, track.at(1_500L)!!.first, 0.001f)
        // The live sample wins where both have one.
        assertEquals(30f, track.at(3_000L)!!.first, 0.001f)
        assertEquals(3_000L, TrackBuilder().apply { add(3_000L, 0f, 0f) }.build(null).startMs)
    }

    @Test
    fun aKeptLiveMessageReadsBackAsItWasReceived() {
        val change = LiveConnection.parse("TimingData\t1700000000000\t0\t{\"Lines\":{}}")!!
        assertEquals("TimingData", change.topic)
        assertEquals(1_700_000_000_000L, change.utc)
        assertTrue(change.data is org.json.JSONObject)
        val state = LiveConnection.parse("Position.z\t5\t1\t\"abc=\"")!!
        assertTrue(state.opening)
        assertEquals("\"abc=\"", state.data)
        assertNull(LiveConnection.parse("#session\t2026/x/"))
        assertNull(LiveConnection.parse("broken"))
    }

    @Test
    fun archiveSpeedPublicationCompactsAndSharesExactTimestamps() {
        val pool = SpeedTimestampPool()
        val firstBuilder = SpeedBuilder()
        val secondBuilder = SpeedBuilder()
        repeat(4101) { i ->
            val now = t0 + i * 250L
            firstBuilder.add(now, 100 + i % 200, i % 9)
            secondBuilder.add(now, 200 + i % 100, i % 9)
        }
        val first = firstBuilder.build(pool)
        val second = secondBuilder.build(pool)
        assertEquals(4101, first.speedTimes.size)
        assertEquals(4101, first.gearTimeSeries.size)
        assertTrue(first.speedTimes === first.gearTimeSeries)
        assertTrue(first.speedTimes === second.speedTimes)
        assertTrue(second.speedTimes === second.gearTimeSeries)
        val last = t0 + 4100 * 250L
        assertEquals(100, first.kmhAt(t0))
        // Different drivers must keep their own value arrays.
        assertEquals(200, second.kmhAt(t0))
        assertEquals(100 + 4100 % 200, first.kmhAt(last))
        assertEquals(200 + 4100 % 100, second.kmhAt(last))
        assertEquals(4100 % 9, first.gearAt(last))
        assertNull(first.kmhAt(last + 5_001L))
        assertNull(first.gearAt(last + 5_001L))
        firstBuilder.add(last + 250L, 350, 8)
        assertEquals(4101, first.speedTimes.size)
        assertEquals(4102, firstBuilder.build(pool).speedTimes.size)
        assertEquals(100 + 4100 % 200, first.kmhAt(last + 250L))
    }

    @Test
    fun archiveSpeedPublicationKeepsIndependentAndEmptySeries() {
        val pool = SpeedTimestampPool()
        val builder = SpeedBuilder()
        builder.add(t0, 200)
        builder.add(t0 + 1000, null, 5)
        builder.add(t0 + 2000, 220, 6)
        val log = builder.build(pool)
        assertTrue(log.speedTimes !== log.gearTimeSeries)
        assertEquals(listOf(t0, t0 + 2000), log.speedTimes.toList())
        assertEquals(listOf(t0 + 1000, t0 + 2000), log.gearTimeSeries.toList())
        assertNull(log.gearAt(t0))
        assertEquals(200, log.kmhAt(t0 + 1000))
        assertEquals(5, log.gearAt(t0 + 1000))
        assertEquals(6, log.gearAt(t0 + 2000))
        val gearOnly = SpeedBuilder().apply { add(t0, null, 0) }.build(pool)
        assertEquals(0, gearOnly.speedTimes.size)
        assertEquals(1, gearOnly.gearTimeSeries.size)
        assertNull(gearOnly.kmhAt(t0))
        assertEquals(0, gearOnly.gearAt(t0))
        val empty = SpeedBuilder().build(pool)
        assertNull(empty.kmhAt(t0))
        assertNull(empty.gearAt(t0))
    }

    @Test
    fun speedTimestampPoolDoesNotConfuseHashCollisionsOrUnusedCapacity() {
        val pool = SpeedTimestampPool()
        val a = longArrayOf(1000, 2000, 9999)
        val b = longArrayOf(1001, 1969)
        val first = pool.freeze(a, 2)
        val collision = pool.freeze(b, 2)
        assertTrue(first !== collision)
        assertEquals(listOf(1000L, 2000L), first.toList())
        assertEquals(listOf(1001L, 1969L), collision.toList())
        assertTrue(first === pool.freeze(longArrayOf(1000, 2000), 2))
        assertTrue(first !== SpeedTimestampPool().freeze(longArrayOf(1000, 2000), 2))
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
