package app.apexview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySampleScheduleTest {
    @Test fun avoidsRedrawingTextAtEveryDisplayRefresh() {
        assertTrue(isOverlaySampleDue(1_000, Long.MIN_VALUE))
        assertFalse(isOverlaySampleDue(1_008, 1_000))
        assertFalse(isOverlaySampleDue(1_016, 1_000))
        assertFalse(isOverlaySampleDue(1_032, 1_000))
        assertTrue(isOverlaySampleDue(1_033, 1_000))
        assertTrue(isOverlaySampleDue(1_050, 1_017))
    }

    @Test fun restartsCleanlyAfterClockReset() {
        assertTrue(isOverlaySampleDue(100, 1_000))
        assertFalse(isOverlaySampleDue(100, 100))
    }
}
