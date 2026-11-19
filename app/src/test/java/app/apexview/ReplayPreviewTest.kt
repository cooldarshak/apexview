package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayPreviewTest {
    @Test
    fun checksArchiveOrderAcrossBomDayPrefixesAndMixedLineEndings() {
        val sorted = "\uFEFF23:59:59.999{}\r\n1.00:00:00.000{}\r1.00:00:00.010{}"
        assertTrue(timingStreamIsChronological(sorted) {})
        assertFalse(timingStreamIsChronological("$sorted\n1.00:00:00.009{}") {})
    }

    @Test
    fun publishesPrimaryStandingsAtThePlayheadAndLeavesTheRestUnread() {
        val lines = listOf(
            """00:00:00.000{"Lines":{"44":{"Position":"2"}}}""",
            """00:00:02.000{"Lines":{"44":{"Position":"1"}}}""",
            """00:00:03.000{"Lines":{"44":{"Position":"3"}}}""",
        ).iterator()
        val preview = DriverTimingParser.previews(lines, 1000L, { 2500L }, {}).first().history
        assertEquals(2, preview.laps.getValue("44").at(2500L)?.position)
        assertEquals(1, preview.laps.getValue("44").at(3000L)?.position)
        assertEquals("""00:00:03.000{"Lines":{"44":{"Position":"3"}}}""", lines.next())
    }

    @Test
    fun streamsFurtherWhenTheVideoClockCorrectsTheResumePosition() {
        val lines = listOf(
            """00:00:00.000{"Lines":{"44":{"Position":"2"}}}""",
            """00:00:02.000{"Lines":{"44":{"Position":"1"}}}""",
            """00:00:30.000{"Lines":{"44":{"Position":"3"}}}""",
            """00:01:00.000{"Lines":{"44":{"Position":"4"}}}""",
        ).iterator()
        var target = 2500L
        val previews = DriverTimingParser.previews(lines, 1000L, { target }, {}).iterator()
        val initial = previews.next()
        target = 30_500L
        val corrected = previews.next()
        assertEquals(3000L, initial.throughUtcMs)
        assertEquals(31_000L, corrected.throughUtcMs)
        assertEquals(3, corrected.history.laps.getValue("44").at(target + 500L)?.position)
        assertEquals(2, initial.history.laps.getValue("44").states.size)
    }

    @Test
    fun defersOutOfOrderHistoryToTheFullMerge() {
        val lines = listOf(
            """00:00:02.000{"Lines":{"44":{"Position":"2"}}}""",
            """00:00:01.000{"Lines":{"44":{"Position":"1"}}}""",
        ).iterator()
        assertNull(DriverTimingParser.previews(lines, 1000L, { 4000L }, {}).firstOrNull())
    }
}
