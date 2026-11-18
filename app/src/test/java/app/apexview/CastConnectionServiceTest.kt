package app.apexview

import android.net.wifi.WifiManager
import android.os.PowerManager
import app.apexview.cast.local.CastConnectionService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastConnectionServiceTest {
    @Test fun connectedDeviceServiceReleasesCpuAndWifiWhenCastingStops() {
        val controller = Robolectric.buildService(CastConnectionService::class.java).create()
        val service = controller.get()
        val cpu = ReflectionHelpers.getField<PowerManager.WakeLock>(service, "cpu")
        val wifi = ReflectionHelpers.getField<WifiManager.WifiLock?>(service, "wifi")
        assertTrue(cpu.isHeld)
        controller.destroy()
        assertFalse(cpu.isHeld)
        assertFalse(wifi?.isHeld == true)
    }
}
