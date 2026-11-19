package app.apexview.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneFrameRatePolicyTest {
    @Test fun suppliesActualVideoCadenceWithoutRoundingToDisplayRefresh() {
        assertEquals(50f, SceneFrameRatePolicy.hint(50.0), 0.0001f)
        assertEquals(29.97f, SceneFrameRatePolicy.hint(29.97), 0.0001f)
        assertEquals(59.94f, SceneFrameRatePolicy.hint(59.94), 0.0001f)
    }

    @Test fun rejectsUnknownOrInvalidCadenceInsteadOfForcingARefreshSwitch() {
        listOf(0.0, -50.0, Double.NaN, Double.POSITIVE_INFINITY, 300.0).forEach {
            assertEquals(0f, SceneFrameRatePolicy.hint(it), 0f)
        }
    }
}
