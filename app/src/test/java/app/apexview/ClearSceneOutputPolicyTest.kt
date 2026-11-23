package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class ClearSceneOutputPolicyTest {
    @Test fun productionDefaultIsTextureView() {
        assertEquals(ClearSceneOutputMode.TEXTURE_VIEW,
            selectClearSceneOutput(false, protectedRollingScene = false, windowCaptureRequired = false))
    }

    @Test fun optedInClearCompositionUsesIndependentSurfaceView() {
        assertEquals(ClearSceneOutputMode.SURFACE_VIEW,
            selectClearSceneOutput(true, protectedRollingScene = false, windowCaptureRequired = false))
    }

    @Test fun protectedVideoIsNeverChangedByClearExperiment() {
        for (enabled in listOf(false, true)) {
            assertEquals(ClearSceneOutputMode.TEXTURE_VIEW,
                selectClearSceneOutput(enabled, protectedRollingScene = true, windowCaptureRequired = false))
        }
    }

    @Test fun castAndReplayRequireOriginalCaptureCompatibleOutput() {
        for (enabled in listOf(false, true)) {
            assertEquals(ClearSceneOutputMode.TEXTURE_VIEW,
                selectClearSceneOutput(enabled, protectedRollingScene = false, windowCaptureRequired = true))
        }
    }

    @Test fun switchingCaptureStateRevertsAndRestoresIndependentLayer() {
        assertEquals(listOf(ClearSceneOutputMode.SURFACE_VIEW, ClearSceneOutputMode.TEXTURE_VIEW,
            ClearSceneOutputMode.TEXTURE_VIEW, ClearSceneOutputMode.SURFACE_VIEW),
            listOf(false, true, true, false).map { capture ->
                selectClearSceneOutput(true, protectedRollingScene = false, windowCaptureRequired = capture)
            })
    }
}
