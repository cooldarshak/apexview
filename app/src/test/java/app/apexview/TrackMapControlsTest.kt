package app.apexview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMapControlsTest {
    @Test
    fun lockedMapControlHidesAfterItsTemporaryVisibilityExpiresOnTouchDevices() {
        assertTrue(shouldShowMapLockControl(mapLocked = true, isTv = false, temporarilyVisible = true))
        assertFalse(shouldShowMapLockControl(mapLocked = true, isTv = false, temporarilyVisible = false))
    }

    @Test
    fun unlockedMapControlAlwaysRemainsVisible() {
        assertTrue(shouldShowMapLockControl(mapLocked = false, isTv = false, temporarilyVisible = false))
    }

    @Test
    fun tvHidesIdleLockAndRevealsItForDpadNavigation() {
        assertFalse(shouldShowMapLockControl(mapLocked = true, isTv = true, temporarilyVisible = false))
        assertTrue(shouldShowMapLockControl(true, true, false, chromeVisible = true))
        assertTrue(shouldShowMapLockControl(true, true, false, lockFocused = true))
    }

    @Test
    fun lockControlAutoHideWindowIsFiveSeconds() {
        assertTrue(MAP_LOCK_CONTROL_AUTO_HIDE_MS == 5_000L)
    }
}
