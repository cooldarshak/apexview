package app.apexview

import org.junit.Assert.*
import org.junit.Test

class SteadyArchiveFreeTrimPolicyTest {
    private val excess = 12 * 1024 * 1024
    @Test fun steadyExcessOnlyAndRateLimited() {
        val p = SteadyArchiveFreeTrimPolicy()
        for (t in 0L..4500L step 500L) assertFalse(p.observe(t, true, excess))
        assertTrue(p.observe(5000, true, excess))
        for (t in 5500L..19500L step 500L) assertFalse(p.observe(t, true, excess))
        assertTrue(p.observe(20000, true, excess))
    }
    @Test fun transientAndDiscontinuousPlaybackCannotTrim() {
        val p = SteadyArchiveFreeTrimPolicy()
        for (t in 0L..5000L step 500L) assertFalse(p.observe(t, true, 0))
        assertFalse(p.observe(5500, true, excess))
        assertFalse(p.observe(6000, false, excess))
        for (t in 6500L..11000L step 500L) assertFalse(p.observe(t, true, excess))
        assertFalse(p.observe(14000, true, excess))
        p.reset()
        assertFalse(p.observe(14500, true, excess))
    }
}
