package app.apexview.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneReleaseStepsTest {
    @Test fun failingCaptureAndEglStepsDoNotSkipOtherOwners() {
        val events = mutableListOf<String>()
        val steps = SceneReleaseSteps { name, _ -> events += "failed:$name" }
        steps.run("capture") { throw IllegalStateException("capture") }
        steps.run("decoder") { events += "decoder" }
        steps.run("egl-window") { throw IllegalStateException("window") }
        steps.run("egl-context") { events += "context" }
        steps.run("surface") { events += "surface" }
        assertEquals(listOf("failed:capture", "decoder", "failed:egl-window", "context", "surface"), events)
    }
}
