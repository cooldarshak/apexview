package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayPreviewTest {
    @Test
    fun publishesPrimaryStandingsAtThePlayheadAndLeavesTheRestUnread() {
        val lines = listOf(
            """00:00:00.000{"Lines":{"44":{"Position":"2"}}}""",
            """00:00:02.000{"Lines":{"44":{"Position":"1"}}}""",
            """00:00:03.000{"Lines":{"44":{"Position":"3"}}}""",
        ).iterator()
        val preview = requireNotNull(DriverTimingParser.preview(lines, 1000L, { 2500L }, {}))
        assertEquals(2, preview.laps.getValue("44").at(2500L)?.position)
        assertEquals(1, preview.laps.getValue("44").at(3000L)?.position)
        assertEquals("""00:00:03.000{"Lines":{"44":{"Position":"3"}}}""", lines.next())
    }

    @Test
    fun defersOutOfOrderHistoryToTheFullMerge() {
        val lines = listOf(
            """00:00:02.000{"Lines":{"44":{"Position":"2"}}}""",
            """00:00:01.000{"Lines":{"44":{"Position":"1"}}}""",
        ).iterator()
        assertNull(DriverTimingParser.preview(lines, 1000L, { 4000L }, {}))
    }
}
