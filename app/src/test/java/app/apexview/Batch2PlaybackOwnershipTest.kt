package app.apexview

import org.junit.Assert.*
import org.junit.Test

class Batch2PlaybackOwnershipTest {
    @Test fun rapidRequestsUseOneFixedWindowAndLatestExactTarget() {
        val seeks = ControlSeekBatch()
        assertTrue(seeks.offer(10_000))
        repeat(20) { assertFalse(seeks.offer(20_000L + it)) }
        assertEquals(20_019L, seeks.take())
        assertNull(seeks.take())
        assertTrue(seeks.offer(50_000))
        seeks.clear()
        assertNull(seeks.targetMs)
    }

    @Test fun bufferingPhasesAreDistinctAndDoNotAssumeNetworkCause() {
        val episodes = PlaybackBufferEpisodes()
        assertNull(episodes.onState(0, true, false, true))
        assertEquals(PlaybackBufferEpisodes.Episode(PlaybackBufferEpisodes.Phase.STARTUP, 500),
            episodes.onState(500, false, true, true))
        episodes.onSeek(590)
        assertNull(episodes.onState(600, true, false, true))
        assertEquals(PlaybackBufferEpisodes.Episode(PlaybackBufferEpisodes.Phase.SEEK, 125),
            episodes.onState(725, false, true, true))
        assertNull(episodes.onState(800, true, false, true))
        assertEquals(PlaybackBufferEpisodes.Episode(PlaybackBufferEpisodes.Phase.REBUFFER, 50),
            episodes.onState(850, false, true, true))
        assertNull(episodes.onState(900, true, false, false))
        assertEquals(PlaybackBufferEpisodes.Phase.PAUSED_BUFFERING,
            episodes.onState(950, false, true, false)?.phase)
    }

    @Test fun onlyOneOwnerCanReleasePlayerAndItsDrmManager() {
        val gate = PlaybackReleaseGate()
        assertTrue(gate.beginRelease())
        repeat(100) { assertFalse(gate.beginRelease()) }
    }
    @Test fun bufferedSeekDoesNotMislabelLaterUnrelatedRebuffer() {
        val episodes = PlaybackBufferEpisodes()
        episodes.onState(0, false, true, true)
        episodes.onSeek(100)
        episodes.onState(4000, true, false, true)
        assertEquals(PlaybackBufferEpisodes.Phase.REBUFFER,
            episodes.onState(4100, false, true, true)?.phase)
    }
}
