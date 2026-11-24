package app.apexview.scene

import org.junit.Assert.*
import org.junit.Test

class ScenePipelineHealthTest {
    @Test fun requiresTwoHealthyWindowsAndRevokesOnOverloadOrReset() {
        val gate = ScenePipelineHealth()
        fun sample(now: Long, frames: Long = 250) =
            gate.observe(now, 5_000_000_000L, 50f, frames, frames, 0, 0, 0, 0, 0, 0)
        sample(5_000_000_000L)
        assertFalse(gate.allows(5_000_000_000L))
        sample(10_000_000_000L)
        assertTrue(gate.allows(10_000_000_000L))
        sample(15_000_000_000L, 200)
        assertFalse(gate.allows(15_000_000_000L))
        sample(20_000_000_000L)
        sample(25_000_000_000L)
        assertTrue(gate.allows(25_000_000_000L))
        assertFalse(gate.allows(33_000_000_000L))
        gate.reset()
        assertFalse(gate.allows(25_000_000_000L))
    }
    @Test fun acceleratedWindowMustActuallySustainItsExpectedRate() {
        val gate = ScenePipelineHealth()
        repeat(2) { gate.observe((it+1)*5_000_000_000L, 5_000_000_000L, 50f, 250, 245, 0, 0, 0, 0, 0, 0) }
        assertTrue(gate.allows(10_000_000_000L))
        gate.observe(15_000_000_000L, 5_000_000_000L, 75f, 250, 245, 0, 0, 0, 0, 0, 0)
        assertFalse(gate.allows(15_000_000_000L))
    }
    @Test fun nonconsecutiveWindowsNeverPromote() {
        val gate = ScenePipelineHealth()
        gate.observe(5_000_000_000L, 5_000_000_000L, 50f, 250, 250, 0, 0, 0, 0, 0, 0)
        gate.observe(30_000_000_000L, 5_000_000_000L, 50f, 250, 250, 0, 0, 0, 0, 0, 0)
        assertFalse(gate.allows(30_000_000_000L))
    }
}
