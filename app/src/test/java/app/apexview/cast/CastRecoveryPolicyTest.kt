package app.apexview.cast
import org.junit.Assert.*
import org.junit.Test

class CastRecoveryPolicyTest {
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
