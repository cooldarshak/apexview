package app.apexview.cast
import org.junit.Assert.*
import org.junit.Test

class CastRecoveryPolicyTest {
    @Test fun invalidStatusCannotAdvanceClockAndLoadingZeroDurationIsValid() {
        assertTrue(CastRecoveryPolicy.validPlaybackStatus(1, 0.0, 0.0, 1.0))
        assertTrue(CastRecoveryPolicy.validPlaybackStatus(2, 10.5, 12.0, 1.5))
        assertTrue(CastRecoveryPolicy.validPlaybackStatus(0, 604800.0, 604800.0, 4.0))
        assertFalse(CastRecoveryPolicy.validPlaybackStatus(-1, 0.0, 0.0, 1.0))
        assertFalse(CastRecoveryPolicy.validPlaybackStatus(1, -1.0, 0.0, 1.0))
        assertFalse(CastRecoveryPolicy.validPlaybackStatus(1, Double.NaN, 0.0, 1.0))
        assertFalse(CastRecoveryPolicy.validPlaybackStatus(1, 0.0, Double.POSITIVE_INFINITY, 1.0))
        assertFalse(CastRecoveryPolicy.validPlaybackStatus(1, 0.0, 0.0, 0.0))
        val owner = CastSessionCoordinator()
        owner.beginLoad("current")
        if (CastRecoveryPolicy.validPlaybackStatus(1, -1.0, 0.0, 1.0)) owner.acceptClock("current", 50)
        assertTrue(owner.acceptClock("current", 1))
        assertEquals(1L, owner.clockEpoch)
    }

    @Test fun confirmedGoogleHandoffUsesReceiverClockWithoutRequiringRelay() {
        assertEquals(12000L to 90000L, CastRecoveryPolicy.handoffClock(12000, 90000, 10000, 80000))
        assertEquals(12000L to 80000L, CastRecoveryPolicy.handoffClock(12000, -1, 10000, 80000))
        assertEquals(10000L to 80000L, CastRecoveryPolicy.handoffClock(null, null, 10000, 80000))
    }
    @Test fun onlyConfirmedSeekableReplaySenderMayRetireMediaPeriods() {
        assertTrue(CastRecoveryPolicy.retireReplayQueues(true, false, 1, false, false, false, true))
        assertFalse(CastRecoveryPolicy.retireReplayQueues(false, false, 1, false, false, false, true))
        assertFalse(CastRecoveryPolicy.retireReplayQueues(true, true, 1, false, false, false, true))
        assertFalse(CastRecoveryPolicy.retireReplayQueues(true, false, 1, true, false, false, true))
        assertFalse(CastRecoveryPolicy.retireReplayQueues(true, false, 1, false, true, false, true))
        assertFalse(CastRecoveryPolicy.retireReplayQueues(true, false, 1, false, false, true, true))
        assertFalse(CastRecoveryPolicy.retireReplayQueues(true, false, 1, false, false, false, false))
    }

    @Test fun bakedLiveUsesDefaultWindowPositionWhileArchiveRetainsExplicitSeek() {
        assertNull(CastRecoveryPolicy.receiverStartPosition(true, 0L, 0L))
        assertNull(CastRecoveryPolicy.receiverStartPosition(true, 60000L, 1000L))
        assertEquals(59000L, CastRecoveryPolicy.receiverStartPosition(false, 60000L, 1000L))
        assertEquals(0L, CastRecoveryPolicy.receiverStartPosition(false, 500L, 1000L))
    }

    @Test fun controllerNeverShowsVideoDuringRemoteOwnershipOrBakedProduction() {
        for (remote in listOf(false, true)) for (baked in listOf(false, true))
            for (replacement in listOf(false, true)) {
                assertEquals(remote || baked || replacement,
                    CastRecoveryPolicy.controllerOnly(remote, baked, replacement))
            }
    }

    @Test fun retryIsBoundedAndRequiresAnAuthenticatedNegotiatedPeer() {
        assertTrue(CastRecoveryPolicy.canRetry(true,true,true,"PEER_EOF",true))
        assertFalse(CastRecoveryPolicy.canRetry(false,true,true,"PEER_EOF",true))
        assertFalse(CastRecoveryPolicy.canRetry(true,false,true,"PEER_EOF",true))
        assertFalse(CastRecoveryPolicy.canRetry(true,true,false,"PEER_EOF",true))
        assertFalse(CastRecoveryPolicy.canRetry(true,true,true,"PEER_EOF",false))
        assertFalse(CastRecoveryPolicy.canRetry(true,true,true,"LOCAL_CLOSE",true))
    }
    @Test fun customLayoutCannotHandOffOnCafStatusAlone() {
        assertFalse(CastRecoveryPolicy.canHandOffGoogle(true,true,true,false))
        assertFalse(CastRecoveryPolicy.canHandOffGoogle(false,true,true,true))
        assertFalse(CastRecoveryPolicy.canHandOffGoogle(true,false,true,true))
        assertTrue(CastRecoveryPolicy.canHandOffGoogle(true,true,true,true))
        assertTrue(CastRecoveryPolicy.canHandOffGoogle(true,true,false,false))
    }
    @Test fun explicitStaleGoogleMediaCannotUpdateTheCurrentClock() {
        assertTrue(CastRecoveryPolicy.matchesGoogleLoad("relay2","load2","relay2","load2"))
        assertTrue(CastRecoveryPolicy.matchesGoogleLoad("relay2","load2","relay2",null))
        assertFalse(CastRecoveryPolicy.matchesGoogleLoad("relay2","load2","relay1","load1"))
        assertFalse(CastRecoveryPolicy.matchesGoogleLoad("relay2","load2","relay2","load1"))
        assertFalse(CastRecoveryPolicy.matchesGoogleLoad("relay2","load2",null,null))
        assertFalse(CastRecoveryPolicy.matchesGoogleLoad(null,"",null,null))
    }

    @Test fun localFirstFrameTimeoutBelongsOnlyToTheUnconfirmedCurrentLoad() {
        assertTrue(CastRecoveryPolicy.localLoadTimedOut("2","2",true,true,true,false))
        assertFalse(CastRecoveryPolicy.localLoadTimedOut("1","2",true,true,true,false))
        assertFalse(CastRecoveryPolicy.localLoadTimedOut("2","2",false,true,true,false))
        assertFalse(CastRecoveryPolicy.localLoadTimedOut("2","2",true,false,true,false))
        assertFalse(CastRecoveryPolicy.localLoadTimedOut("2","2",true,true,false,false))
        assertFalse(CastRecoveryPolicy.localLoadTimedOut("2","2",true,true,true,true))
    }
    @Test fun renderingAckAndAdoptedMediaMayArriveInEitherOrder() {
        val ack = CastRecoveryPolicy.matchesRenderingAck("load2", "load2")
        assertTrue(ack)
        assertFalse(CastRecoveryPolicy.canHandOffGoogle(false, true, true, ack))
        assertTrue(CastRecoveryPolicy.canHandOffGoogle(true, true, true, ack))
        assertFalse(CastRecoveryPolicy.canHandOffGoogle(true, true, true,
            CastRecoveryPolicy.matchesRenderingAck("load2", null)))
        assertFalse(CastRecoveryPolicy.matchesRenderingAck("load2", "load1"))
        assertFalse(CastRecoveryPolicy.matchesRenderingAck("", ""))
        assertFalse(CastRecoveryPolicy.matchesRenderingAck(" ", " "))
    }
    @Test fun pendingUserControlsAreLatestOnlyAndOwnedByTheAdoptedLoad() {
        val controls = GooglePendingLoadControls()
        controls.begin("first")
        controls.play("first", true)
        controls.seek("first", 120)
        controls.begin("second")
        controls.play("first", false)
        controls.seek("first", 100)
        assertNull(controls.take("second", true))
        controls.play("second", true)
        controls.play("second", false)
        controls.seek("second", 200)
        controls.seek("second", 350)
        assertNull(controls.take("second", false))
        assertNull(controls.take("first", true))
        assertEquals(GooglePendingLoadControls.Commands(false, 350L), controls.take("second", true))
        assertNull(controls.take("second", true))
        controls.begin("")
        controls.play("", true)
        assertNull(controls.take("", true))
    }
}
