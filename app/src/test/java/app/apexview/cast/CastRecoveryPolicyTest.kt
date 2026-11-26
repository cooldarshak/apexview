package app.apexview.cast
import org.junit.Assert.*
import org.junit.Test

class CastRecoveryPolicyTest {
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
}
