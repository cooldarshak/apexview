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
    @Test fun receiverRoundTripPreservesMainPlusThreeOverlapAndCodedPadding() {
        val windows = listOf(VideoWindow(1, 0, 0f, 0f, 1440f, 810f, 0f)) +
            (1..3).map { i -> VideoWindow(i + 1, i, 1440f, (i - 1) * 270f, 480f, 270f, i.toFloat()) }
        val original = VideoScene(1920f, 1080f, 2560, 1472, 4, windows, 2, 1280, 736, 1280, 720)
        assertEquals(original, CastSceneProtocol.decode(CastSceneProtocol.encode(original)))
        val moved = original.copy(windows = windows.map { if (it.id == 4) it.copy(x = 800f, y = 100f, width = 720f, height = 405f, z = 10f) else it })
        val decoded = CastSceneProtocol.decode(CastSceneProtocol.encode(moved))
        assertEquals(4, decoded.quads().last().id)
        assertArrayEquals(moved.quads().last().texture, decoded.quads().last().texture, 0f)
        // Single sources may have outer SPS padding cropped from the display picture.
        val single = VideoScene(1920f, 1080f, 1920, 1080, 1, listOf(windows.first()), 1, 1920, 1088, 1920, 1080)
        assertEquals(single, CastSceneProtocol.decode(CastSceneProtocol.encode(single)))
    }
    @Test fun receiverRejectsFalseGeometryAndUnknownSourceMappings() {
        fun valid() = CastSceneProtocol.encode(VideoScene(1920f, 1080f, 2560, 1472, 4,
            (0..3).map { VideoWindow(it, it, (it % 2) * 960f, (it / 2) * 540f, 960f, 540f, 0f) }, 2, 1280, 736, 1280, 720))
        for (bad in listOf(valid().put("sourceDisplayHeight", 0), valid().put("sources", 25),
            valid().put("atlasWidth", 1280), valid().put("height", 0), valid().put("version", 2))) {
            assertThrows(IllegalArgumentException::class.java) { CastSceneProtocol.decode(bad) }
        }
        val bad = valid()
        bad.getJSONArray("windows").getJSONObject(0).put("source", 4)
        assertThrows(IllegalArgumentException::class.java) { CastSceneProtocol.decode(bad) }
    }

}
