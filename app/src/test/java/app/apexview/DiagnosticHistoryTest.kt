package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticHistoryTest {
    @Test
    fun summarizesOneSurfaceWithoutDroppingOtherErrors() {
        val history = DiagnosticHistory()
        val first = "11-18 E BufferQueueProducer: [surface A] dequeueBuffer: waitForFreeSlotThenRelock: timeout count=20"
        val repeat = first.replace("count=20", "count=21")
        val second = first.replace("surface A", "surface B")
        assertEquals(first, history.accept(first))
        assertNull(history.accept(repeat))
        assertEquals(second, history.accept(second))
        assertEquals("E BufferQueueProducer: abandoned", history.accept("E BufferQueueProducer: abandoned"))
        assertEquals(listOf("BUFFER_QUEUE_TIMEOUT repeats=1 last=$repeat"), history.drain())
        assertEquals(repeat, history.accept(repeat))
    }

    @Test
    fun retainsRepresentativeAndroid16FrameRateWarningAndCountsEveryRepeat() {
        val history = DiagnosticHistory()
        val line = "11-23 I View    : setRequestedFrameRate frameRate=NaN, caller=androidx.compose.ui.platform.Api35Impl.setRequestedFrameRate"
        val next = line.replace("11-23", "11-24")
        val protected = "I ApexviewSingleDecoder: PROTECTED_CMAF_SEGMENT feeds=6"
        assertEquals(line, history.accept(line))
        assertNull(history.accept(next))
        assertNull(history.accept(next))
        assertEquals(protected, history.accept(protected))
        assertEquals(listOf("PLATFORM_FRAME_RATE_NAN repeats=2 last=$next"), history.drain())
        assertEquals(line, history.accept(line))
        history.clear()
        assertEquals(line, history.accept(line))
        assertEquals(emptyList<String>(), history.drain())
    }

    @Test
    fun carriesTheReplayStartAcrossLogRotation() {
        val history = DiagnosticHistory()
        val start = "I ApexviewTiming: Starting timing load for content=42 trace=100"
        history.retain(start)
        repeat(130) { history.retain("I ApexviewTiming: stage $it trace=100") }
        assertEquals(start, history.context().first())
        assertTrue(history.context().last().contains("stage 129"))
        assertEquals(97, history.context().size)
    }
}
