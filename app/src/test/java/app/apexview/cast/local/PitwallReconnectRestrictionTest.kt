package app.apexview.cast.local

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PitwallReconnectRestrictionTest {
    @Test fun onlyExplicitSelectionClearsAStoredCertificatePairRestriction() {
        val context = RuntimeEnvironment.getApplication()
        val first = PitwallRememberedPeers.key(byteArrayOf(1),byteArrayOf(2))
        val other = PitwallRememberedPeers.key(byteArrayOf(1),byteArrayOf(3))
        PitwallRememberedPeers.forgetAll(context)
        try {
            PitwallRememberedPeers.requireManualReconnect(context,first)
            assertFalse(PitwallRememberedPeers.requiresManualReconnect(context,first))
            PitwallRememberedPeers.remember(context,first)
            PitwallRememberedPeers.requireManualReconnect(context,first)
            assertTrue(PitwallRememberedPeers.contains(context,first))
            assertTrue(PitwallRememberedPeers.requiresManualReconnect(context,first))
            PitwallRememberedPeers.remember(context,other)
            PitwallRememberedPeers.remember(context,first)
            assertTrue(PitwallRememberedPeers.requiresManualReconnect(context,first))
            assertFalse(PitwallRememberedPeers.requiresManualReconnect(context,other))
            PitwallRememberedPeers.allowManualReconnect(context,first)
            assertFalse(PitwallRememberedPeers.requiresManualReconnect(context,first))
            assertTrue(PitwallRememberedPeers.contains(context,first))
            PitwallRememberedPeers.requireManualReconnect(context,first)
            PitwallRememberedPeers.forgetAll(context)
            PitwallRememberedPeers.remember(context,first)
            assertFalse(PitwallRememberedPeers.requiresManualReconnect(context,first))
        } finally { PitwallRememberedPeers.forgetAll(context) }
    }
}
