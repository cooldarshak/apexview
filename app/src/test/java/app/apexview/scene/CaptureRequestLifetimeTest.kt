package app.apexview.scene

import org.junit.Assert.*
import org.junit.Test

class CaptureRequestLifetimeTest {
    @Test fun stoppingWaitsForOutstandingCopy() {
        val owner = CaptureRequestLifetime()
        assertTrue(owner.begin())
        assertFalse(owner.begin())
        assertFalse(owner.stop())
        assertFalse(owner.begin())
        owner.complete()
        assertTrue(owner.stop())
        assertFalse(owner.begin())
    }
    @Test fun completionAllowsNextFrameUntilStopped() {
        val owner = CaptureRequestLifetime()
        assertTrue(owner.begin())
        owner.complete()
        assertTrue(owner.begin())
        owner.complete()
        assertTrue(owner.stop())
        assertFalse(owner.begin())
    }
}
