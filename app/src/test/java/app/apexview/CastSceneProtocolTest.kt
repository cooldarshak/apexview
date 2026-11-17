package app.apexview

import app.apexview.cast.CastSceneProtocol
import app.apexview.scene.VideoScene
import app.apexview.scene.VideoWindow
import org.junit.Assert.*
import org.junit.Test

class CastSceneProtocolTest {
    @Test fun mainAndSideWindowsPreserveCoordinatesSourceOrderAndZ() {
        val windows = listOf(VideoWindow(10, 2, 0f, 0f, 1440f, 810f, 0f)) +
            listOf(0, 1, 3).mapIndexed { i, source ->
                VideoWindow(20 + i, source, 1440f, i * 270f, 480f, 270f, 1f)
            }
        val scene = VideoScene(1920f, 1080f, 2560, 1472, 4, windows, 2, 1280, 736, 1280, 720)
        val json = CastSceneProtocol.encode(scene)
        assertEquals(720, json.getInt("sourceDisplayHeight"))
        val rows = json.getJSONArray("windows")
        assertEquals(4, rows.length())
        assertEquals(2, rows.getJSONObject(0).getInt("source"))
        assertEquals(1440.0, rows.getJSONObject(0).getDouble("width"), 0.0)
        assertEquals(1440.0, rows.getJSONObject(1).getDouble("x"), 0.0)
        assertEquals(540.0, rows.getJSONObject(3).getDouble("y"), 0.0)
        assertFalse(json.toString().contains("http"))
    }
}
