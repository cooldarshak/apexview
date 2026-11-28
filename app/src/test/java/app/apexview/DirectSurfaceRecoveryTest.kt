package app.apexview

import org.junit.Assert.*
import org.junit.Test

class DirectSurfaceRecoveryTest {
    @Test fun destroyedOrReboundSurfaceRejectsOldRendererAcknowledgements() {
        val epoch = DirectSurfaceEpoch()
        assertFalse(epoch.accepts(true, 10))
        epoch.created(100)
        assertFalse(epoch.accepts(true, 99))
        assertFalse(epoch.accepts(false, 101))
        assertTrue(epoch.accepts(true, 101))
        epoch.destroyed()
        assertFalse(epoch.available)
        assertFalse(epoch.accepts(true, 150))
        epoch.rebind(151)
        assertFalse(epoch.available)
        epoch.created(200)
        assertFalse(epoch.accepts(true, 150))
        assertTrue(epoch.accepts(true, 201))
        epoch.rebind(300)
        assertFalse(epoch.accepts(true, 201))
        assertTrue(epoch.accepts(true, 301))
    }
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
