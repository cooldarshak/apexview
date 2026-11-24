package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedReadoutScaleTest {
    @Test fun defaultsToSmallerThanPreviousBadge() {
        assertEquals(0.70f, SpeedReadoutScale.DEFAULT, 0f)
        assertEquals(11.9f, 17f * SpeedReadoutScale.DEFAULT, 0.001f)
    }

    @Test fun clampsPersistedAndEditedValues() {
        assertEquals(SpeedReadoutScale.MIN, SpeedReadoutScale.clamp(-3f), 0f)
        assertEquals(1f, SpeedReadoutScale.clamp(1f), 0f)
        assertEquals(SpeedReadoutScale.MAX, SpeedReadoutScale.clamp(10f), 0f)
        assertEquals(SpeedReadoutScale.DEFAULT, SpeedReadoutScale.clamp(Float.NaN), 0f)
        assertEquals(SpeedReadoutScale.DEFAULT, SpeedReadoutScale.clamp(Float.POSITIVE_INFINITY), 0f)
    }
}
