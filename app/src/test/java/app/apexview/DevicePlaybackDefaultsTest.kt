package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePlaybackDefaultsTest {
    @Test fun freshTvUsesConservativeCapAndPhoneKeepsAuto() {
        assertEquals(VideoQuality.P540, DevicePlaybackDefaults.quality(null, true, true))
        assertEquals(VideoQuality.P720, DevicePlaybackDefaults.quality(null, true, false))
        assertEquals(VideoQuality.AUTO, DevicePlaybackDefaults.quality(null, false, true))
    }
    @Test fun storedUserChoicesAlwaysWin() {
        for (quality in VideoQuality.entries) {
            assertEquals(quality, DevicePlaybackDefaults.quality(quality.name, true, true))
        }
        assertEquals(VideoQuality.AUTO, DevicePlaybackDefaults.quality("unknown", true, false))
    }
}
