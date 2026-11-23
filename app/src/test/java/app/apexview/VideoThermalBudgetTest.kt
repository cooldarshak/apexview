package app.apexview

import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Test

class VideoThermalBudgetTest {
    @Test fun preservesExistingMultidecoderThermalBounds() {
        assertEquals(1f, VideoThermalBudget.multipleDecoderScale(PowerManager.THERMAL_STATUS_LIGHT), 0f)
        assertEquals(0.75f, VideoThermalBudget.multipleDecoderScale(PowerManager.THERMAL_STATUS_MODERATE), 0f)
        assertEquals(0.5f, VideoThermalBudget.multipleDecoderScale(PowerManager.THERMAL_STATUS_SEVERE), 0f)
        assertFalse(VideoThermalBudget.mainVideoOnly(PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(VideoThermalBudget.mainVideoOnly(PowerManager.THERMAL_STATUS_CRITICAL))
    }
    @Test fun describesQualityImpactAndRecoveryInsteadOfSilentlyReducingIt() {
        assertNull(VideoThermalBudget.transitionNotice(PowerManager.THERMAL_STATUS_LIGHT,
            PowerManager.THERMAL_STATUS_NONE))
        assertTrue(VideoThermalBudget.transitionNotice(PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_LIGHT)!!.contains("Multiple-decoder"))
        assertTrue(VideoThermalBudget.transitionNotice(PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_MODERATE)!!.contains("Single-decoder atlas quality is unchanged"))
        assertTrue(VideoThermalBudget.transitionNotice(PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_SEVERE)!!.contains("only the main video"))
        assertTrue(VideoThermalBudget.transitionNotice(PowerManager.THERMAL_STATUS_LIGHT,
            PowerManager.THERMAL_STATUS_MODERATE)!!.contains("cooled"))
        assertNull(VideoThermalBudget.transitionNotice(PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_SEVERE))
        assertNotNull(VideoThermalBudget.startupNotice(PowerManager.THERMAL_STATUS_SEVERE))
        assertNull(VideoThermalBudget.startupNotice(PowerManager.THERMAL_STATUS_NONE))
    }
}
