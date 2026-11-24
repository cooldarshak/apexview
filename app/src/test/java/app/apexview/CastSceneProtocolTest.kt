package app.apexview

import app.apexview.cast.CastSceneProtocol
import app.apexview.scene.VideoScene
import app.apexview.scene.VideoWindow
import app.apexview.scene.VideoSourceRect
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
    @Test fun pitwallCastRoundTripPreservesRealMixedProtectedAtlasAndEveryFeedCrop() {
        val rects = listOf(
            VideoSourceRect(0, 0, 1920, 1080),
            VideoSourceRect(0, 1088, 960, 540),
            VideoSourceRect(960, 1088, 960, 540),
            VideoSourceRect(0, 1632, 960, 540)
        )
        // This is the same 1920x2176 single-decoder atlas that was shown
        // raw in a tiny tile on Build 691. Both local Pitwall Cast's clear
        // relay and native Widevine relay use the same versioned scene codec.
        val windows = listOf(
            VideoWindow(10, 0, 0f, 185f, 1296f, 729f, 0f),
            VideoWindow(11, 1, 1296f, 185f, 334f, 188f, 1f),
            VideoWindow(12, 2, 1296f, 380f, 334f, 188f, 2f),
            VideoWindow(13, 3, 1296f, 575f, 334f, 188f, 3f)
        )
        val scene = VideoScene(2316f, 1080f, 1920, 2176, 4, windows,
            atlasColumns = 2, sourceCodedWidth = 1920, sourceCodedHeight = 1088,
            sourceDisplayWidth = 1920, sourceDisplayHeight = 1080, sourceRects = rects)
        val decoded = CastSceneProtocol.decode(CastSceneProtocol.encode(scene))
        assertEquals(scene, decoded)
        assertEquals(4, decoded.windows.size)
        assertEquals(4, decoded.quads().map { it.texture.toList() }.distinct().size)
        assertEquals(windows.map { it.id }, decoded.quads().map { it.id })
        assertTrue(decoded.quads()[0].positions[0] < decoded.quads()[1].positions[0])
        val relocated = scene.copy(windows = windows.map {
            if (it.source == 2) it.copy(x = 1640f, y = 330f, z = 12f) else it
        })
        val moved = CastSceneProtocol.decode(CastSceneProtocol.encode(relocated))
        assertEquals(relocated, moved)
        assertArrayEquals(scene.quads().first { it.id == 12 }.texture,
            moved.quads().first { it.id == 12 }.texture, 0f)
        assertEquals(12, moved.quads().last().id)
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
