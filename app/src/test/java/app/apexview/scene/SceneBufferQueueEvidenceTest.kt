package app.apexview.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneBufferQueueEvidenceTest {
    @Test fun sustainedDecodeAndGlFlowCannotBeMisreportedAsVideoStall() {
        assertEquals(SceneBufferQueueEvidence.Stage.GL_FLOW_OBSERVED,
            SceneBufferQueueEvidence.stage(250, 249, 0, 0, 0))
    }
    @Test fun onlyObservedGlSymptomsMayBeAttributed() {
        assertEquals(SceneBufferQueueEvidence.Stage.EGL_SWAP_DELAY_OBSERVED,
            SceneBufferQueueEvidence.stage(249, 200, 0, 2, 0))
        assertEquals(SceneBufferQueueEvidence.Stage.GL_OUTPUT_UNAVAILABLE,
            SceneBufferQueueEvidence.stage(249, 0, 0, 0, 3))
        assertEquals(SceneBufferQueueEvidence.Stage.CALLBACK_GAP_OBSERVED,
            SceneBufferQueueEvidence.stage(90, 80, 2, 0, 0))
        assertEquals(SceneBufferQueueEvidence.Stage.NO_LATCH_OBSERVED,
            SceneBufferQueueEvidence.stage(0, 0, 0, 0, 0))
        assertEquals(SceneBufferQueueEvidence.Stage.GL_OUTPUT_UNAVAILABLE,
            SceneBufferQueueEvidence.stage(0, 0, 0, 0, 2))
    }
}
