package app.apexview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independently finished previews cannot replace a newer track/tower state. */
class TimingPreviewEpochsTest {
    @Test fun newerTrackAndTowerPublicationsSupersedeOlderWorkers() {
        val gate = TimingPreviewEpochs()
        val positions = gate.next()
        assertTrue(gate.isCurrent(positions))
        val tower = gate.next()
        assertFalse(gate.isCurrent(positions))
        assertTrue(gate.isCurrent(tower))
        val newest = gate.next()
        assertFalse(gate.isCurrent(tower))
        assertFalse(gate.isCurrent(positions))
        assertTrue(gate.isCurrent(newest))
    }
}
