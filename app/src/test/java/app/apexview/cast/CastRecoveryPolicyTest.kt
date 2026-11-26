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
}
