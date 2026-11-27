package app.apexview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootBackNavigationTest {
    @Test fun idleHomeDelegatesBackToPlatformOnPhoneAndTv() {
        assertFalse(handlesRootBack(1, false, false, false, false))
    }

    @Test fun internalNavigationAndPlaybackStillOwnBack() {
        assertTrue(handlesRootBack(2, false, false, false, false))
        assertTrue(handlesRootBack(1, true, false, false, false))
        assertTrue(handlesRootBack(1, false, true, false, false))
        assertTrue(handlesRootBack(1, false, false, true, false))
        assertTrue(handlesRootBack(1, false, false, false, true))
    }

    @Test fun successiveBackActionsEventuallyReachPlatform() {
        assertTrue(handlesRootBack(2, true, true, false, false))
        assertTrue(handlesRootBack(2, true, false, false, false))
        assertTrue(handlesRootBack(1, true, false, false, false))
        assertFalse(handlesRootBack(1, false, false, false, false))
    }
}
