package app.apexview.cast

import app.apexview.tiled.CmafBoxInspector
import org.junit.Assert.*
import org.junit.Test

class RollingWebVttTest {
    @Test fun timestampMapAlignsAndClipsActualCuesIncludingOverlaps() {
        val source = """WEBVTT
            |X-TIMESTAMP-MAP=LOCAL:00:00:10.000,MPEGTS:9000000
            |
            |cue-a
            |00:00:10.100 --> 00:00:12.500 line:80%
            |First cue
            |
            |00:00:11.000 --> 00:00:13.500
            |Second cue
        """.trimMargin()
        val start = 1_700_000_000_000_000L
        val cues = RollingWebVtt.cues(source, start, start + 3_000_000, start, 100_000_000)
        assertEquals(listOf("First cue", "Second cue"), cues.map { it.text })
        assertEquals(start + 100_000, cues[0].startUs)
        assertEquals(start + 3_000_000, cues[1].endUs)
        assertEquals("line:80%", cues[0].settings)
        val initialization = CmafBoxInspector.inspect(WebVttCmaf.initialization())
        assertTrue(initialization.errors.toString(), initialization.errors.isEmpty())
        assertEquals("wvtt", initialization.tracks.single().sampleEntry)
        val fragment = WebVttCmaf.fragment(1, 7_000_000, start, start + 3_000_000, cues)
        val report = CmafBoxInspector.inspect(fragment, initialization)
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        val timing = report.fragments.single()
        assertTrue(timing.timingComplete)
        assertEquals(7_000_000L, timing.baseDecodeTimeTicks)
        assertEquals(3_000_000L, timing.totalDurationTicks)
        assertEquals(listOf(100_000L, 900_000L, 1_500_000L, 500_000L),
            timing.sampleTimings.map { it.durationTicks })
    }

    @Test fun initializationMapAndMpegTsWrapAreAnchoredToTheObservedVideoEpoch() {
        val wrapped = 1L shl 33
        val raw = (wrapped + 90_000) * 100 / 9
        val result = RollingWebVtt.cues("00:00:00.005 --> 00:00:00.100\nThe cue",
            0, 1_000_000, 0, raw, "WEBVTT\r\nX-TIMESTAMP-MAP=MPEGTS:90000,LOCAL:00:00:00.000\r\n\r\n")
        assertEquals(5_000L, result.single().startUs)
        assertEquals(100_000L, result.single().endUs)
    }

    @Test fun wrongClockAndNonWebVttFailWithoutPublishingCues() {
        val raw = "WEBVTT\n\n00:00:45.000 --> 00:00:46.000\nWrong segment"
        assertThrows(IllegalArgumentException::class.java) { RollingWebVtt.cues(raw, 0, 1_000_000, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { RollingWebVtt.cues("not WebVTT", 0, 1_000_000, 0, 0) }
        assertTrue(RollingWebVtt.cues("WEBVTT\n\n", 0, 1_000_000, 0, 0).isEmpty())
    }
}
