package app.apexview.tiled

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsDiscontinuityBoundaryGateTest {
    private val labels = listOf("main", "onboard")

    @Test fun independentPlaylistSequenceOffsetsDoNotRejectAlignedMedia() {
        val gate = HlsDiscontinuityBoundaryGate()
        assertTrue(gate.accepts(labels, listOf(6, 27), 6_000_000L))
        gate.commit(labels, listOf(6, 27), 6_000_000L)
        assertTrue(gate.accepts(labels, listOf(6, 27), 11_760_000L))
        gate.commit(labels, listOf(6, 27), 11_760_000L)
        assertTrue(gate.accepts(labels, listOf(7, 28), 17_520_000L))
    }

    @Test fun changingOnlyOneFeedDiscontinuityFailsClosed() {
        val gate = HlsDiscontinuityBoundaryGate()
        gate.commit(labels, listOf(6, 27), 6_000_000L)
        assertFalse(gate.accepts(labels, listOf(7, 27), 11_760_000L))
        assertFalse(gate.accepts(labels, listOf(6, 28), 11_760_000L))
        assertFalse(gate.accepts(labels, listOf(5, 27), 11_760_000L))
        assertFalse(gate.accepts(labels.reversed(), listOf(6, 27), 11_760_000L))
    }

    @Test fun seekCanRebasePeriodButFollowingTransitionsRemainChecked() {
        val gate = HlsDiscontinuityBoundaryGate()
        gate.commit(labels, listOf(6, 27), 100_000_000L)
        assertTrue(gate.accepts(labels, listOf(3, 15), 20_000_000L))
        gate.commit(labels, listOf(3, 15), 20_000_000L)
        assertFalse(gate.accepts(labels, listOf(4, 15), 25_760_000L))
        assertTrue(gate.accepts(labels, listOf(4, 16), 25_760_000L))
        gate.reset()
        assertTrue(gate.accepts(labels, listOf(2, 99), 25_760_000L))
    }
}
