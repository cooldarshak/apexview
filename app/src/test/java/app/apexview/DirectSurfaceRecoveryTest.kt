package app.apexview

import org.junit.Assert.*
import org.junit.Test

class DirectSurfaceRecoveryTest {
    @Test fun oneRetryThenFailureOnlyForReadyVideo() {
        val r = DirectSurfaceRecovery()
        assertEquals(DirectSurfaceRecovery.Action.NONE, r.observe(0, false))
        assertEquals(DirectSurfaceRecovery.Action.NONE, r.observe(20_000, true))
        assertEquals(DirectSurfaceRecovery.Action.NONE, r.observe(27_999, true))
        assertEquals(DirectSurfaceRecovery.Action.REBIND, r.observe(28_000, true))
        assertEquals(DirectSurfaceRecovery.Action.NONE, r.observe(36_000, false))
        assertEquals(DirectSurfaceRecovery.Action.NONE, r.observe(60_000, true))
        assertEquals(DirectSurfaceRecovery.Action.FAILED, r.observe(68_000, true))
        r.acknowledge()
        assertEquals(DirectSurfaceRecovery.Action.NONE, r.observe(100_000, true))
        r.newTarget()
        assertEquals(DirectSurfaceRecovery.Action.NONE, r.observe(101_000, true))
        assertEquals(DirectSurfaceRecovery.Action.REBIND, r.observe(109_000, true))
    }
}
