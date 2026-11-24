package app.apexview.scene

import app.apexview.LayoutMode
import app.apexview.Layouts
import app.apexview.OverlayLayoutMode
import org.junit.Assert.*
import org.junit.Test

class VideoSceneTest {
    @Test fun mainGridAndTvReuseTheExistingLayoutGeometryWithoutChangingSourceOrder() {
        for (mode in listOf(LayoutMode.FEATURED, LayoutMode.GRID, LayoutMode.TV)) {
            val slots = if (mode == LayoutMode.TV) Layouts.withCombinedOverlays(mode, 3, 1920f, 1080f,
                OverlayLayoutMode.DOCKED).slots else Layouts.place(mode, 3, 1920f, 1080f)
            val scene = VideoScene(1920f, 1080f, 2880, 540, 3,
                slots.mapIndexed { i, slot -> VideoWindow(100 + i, i, slot.x, slot.y, slot.w, slot.h, i.toFloat()) })
            val quads = scene.quads()
            assertEquals(3, quads.size)
            for (i in quads.indices) {
                assertEquals(slots[i].x / 960f - 1, quads[i].positions[0], 0.0001f)
                assertEquals(i / 3f + 0.5f / 2880, quads[i].texture[0], 0.0001f)
            }
        }
    }

    @Test fun freeOverlapsFollowZOrderAndCameraSamplingStaysWithTheWindow() {
        val windows = listOf(VideoWindow(10, 0, -200f, 80f, 900f, 506.25f, 9f),
            VideoWindow(11, 1, 100f, 120f, 1200f, 675f, 2f))
        val quads = VideoScene(1920f, 1080f, 1920, 540, 2, windows).quads()
        assertEquals(listOf(11, 10), quads.map { it.id })
        assertTrue(quads.last().positions[0] < -1) // GPU clips offscreen pixels, preserving source crop.
        assertTrue(quads.first().texture[0] > 0.5f)
        assertTrue(quads.last().texture[2] < 0.5f)
    }


    @Test fun protectedVegas1920By2176AtlasKeepsFourTVWindowsInWidescreenPositions() {
        // Build 637 decoded all four cameras into 1920x2176 but mistakenly
        // showed that tall coded atlas as the one video viewport. The GL
        // renderer must instead use source rectangles for the existing UI
        // layout, preserving the actual decoder SPS size unchanged.
        val canvasWidth = 2316f
        val canvasHeight = 1080f
        val slots = Layouts.withCombinedOverlays(
            LayoutMode.TV, 4, canvasWidth, canvasHeight, OverlayLayoutMode.DOCKED
        ).slots
        assertEquals(4, slots.size)
        val originalWindows = slots.mapIndexed { index, slot ->
            VideoWindow(index + 10, index, slot.x, slot.y, slot.w, slot.h, index.toFloat())
        }
        val src = listOf(
            VideoSourceRect(0, 0, 1920, 1080),
            VideoSourceRect(0, 1088, 960, 540),
            VideoSourceRect(960, 1088, 960, 540),
            VideoSourceRect(0, 1632, 960, 540)
        )
        fun scene(windows: List<VideoWindow>) = VideoScene(
            canvasWidth, canvasHeight, 1920, 2176, 4, windows,
            atlasColumns = 2, sourceCodedWidth = 1920,
            sourceCodedHeight = 1088, sourceDisplayWidth = 1920,
            sourceDisplayHeight = 1080, sourceRects = src, sourceFrameRate = 50.0
        )
        val quads = scene(originalWindows).quads()
        assertEquals(4, quads.size)
        assertEquals(originalWindows.map { it.id }, quads.map { it.id })
        assertEquals(src.size, quads.map { it.texture.toList() }.distinct().size)
        assertEquals(slots.first().x * 2 / canvasWidth - 1,
            quads.first().positions[0], 0.0001f)
        val moved = originalWindows.reversed().mapIndexed { i, win ->
            win.copy(x = (i * 60).toFloat(), y = (i * 90).toFloat(),
                z = (4 - i).toFloat())
        }
        val reconfigured = scene(moved).quads()
        assertEquals(4, reconfigured.size)
        assertEquals(
            quads.associateBy { it.id }.mapValues { it.value.texture.toList() },
            reconfigured.associateBy { it.id }.mapValues { it.value.texture.toList() }
        )
    }

    @Test fun protectedFourFeed3840By2176WideAtlasMapsEach1080And540Picture() {
        // Real Build 674's heavy Las Vegas qualities produce a 3840x2176 picture
        // with three 1920x1088 coded sources and a 960x544 coded source.
        // No second video decoder is needed to display four independently
        // positioned output windows. The actual camera crop rectangles are
        // established at runtime from independently validated HEVC SPS metadata.
        val width = 2316f
        val height = 1080f
        val sourceRects = listOf(
            VideoSourceRect(0, 0, 1920, 1080),
            VideoSourceRect(1920, 0, 1920, 1080),
            VideoSourceRect(0, 1088, 1920, 1080),
            VideoSourceRect(1920, 1088, 960, 540),
        )
        val windows = Layouts.withCombinedOverlays(
            LayoutMode.TV, 4, width, height, OverlayLayoutMode.DOCKED
        ).slots.mapIndexed { index, slot ->
            VideoWindow(90 + index, index, slot.x, slot.y, slot.w, slot.h, index.toFloat())
        }
        val scene = VideoScene(width, height, 3840, 2176, 4, windows,
            atlasColumns = 2, sourceCodedWidth = 1920, sourceCodedHeight = 1088,
            sourceDisplayWidth = 1920, sourceDisplayHeight = 1080,
            sourceRects = sourceRects, sourceFrameRate = 50.0)
        val quads = scene.quads()
        assertEquals(4, quads.size)
        assertEquals(4, quads.map { it.texture.toList() }.distinct().size)
        assertEquals(windows.map { it.id }, quads.map { it.id })
        assertTrue(quads.all { q -> q.texture.all { it in 0f..1f } })
        assertTrue(quads[0].texture[2] < quads[1].texture[0])
        assertTrue(quads[2].texture[2] < quads[3].texture[0])
    }

    @Test fun sourceEdgesCannotSamplePixelsFromAnAdjacentCamera() {
        val scene = VideoScene(1920f, 1080f, 2880, 540, 3,
            (0..2).map { VideoWindow(it, it, 0f, 0f, 960f, 540f, it.toFloat()) })
        for ((i, quad) in scene.quads().withIndex()) {
            assertTrue(quad.texture[0] > i / 3f)
            assertTrue(quad.texture[2] < (i + 1) / 3f)
            assertTrue(quad.texture[1] > 0f)
            assertTrue(quad.texture[5] < 1f)
        }
    }

    @Test fun portraitAndLandscapeHaveIndependentCoordinatesForTheSameCamera() {
        for ((w, h) in listOf(1080f to 1920f, 1920f to 1080f)) {
            val slot = Layouts.place(LayoutMode.FEATURED, 3, w, h).first()
            val q = VideoScene(w, h, 2880, 540, 3, listOf(VideoWindow(1, 2, slot.x, slot.y, slot.w, slot.h, 0f))).quads().single()
            assertEquals(2 / 3f + 0.5f / 2880, q.texture[0], 0.0001f)
            assertTrue(q.positions.all { it.isFinite() })
        }
    }

    @Test fun invalidGeometryIsRejectedBeforeItReachesGles() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f)) {
            try { VideoScene(100f, 100f, 960, 540, 1, listOf(VideoWindow(1, 0, 0f, 0f, bad, 10f, 0f))).quads(); fail() }
            catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun twoDimensionalAtlasUsesCodedStridesAndCropsPaddingForEveryWindow() {
        // Each source is coded 480x272 with a 480x270 visible picture. The final
        // decoder output has its own bottom crop, so row boundaries are not halves.
        val scene = VideoScene(1920f, 1080f, 960, 542, 4,
            (0..3).map { VideoWindow(it, it, 0f, 0f, 100f, 100f, it.toFloat()) },
            atlasColumns = 2, sourceCodedWidth = 480, sourceCodedHeight = 272,
            sourceDisplayWidth = 480, sourceDisplayHeight = 270)
        val quad = scene.quads()
        assertEquals(1f - 270f / 542f + .5f / 542f, quad[0].texture[1], .0001f)
        assertEquals(.5f / 542f, quad[2].texture[1], .0001f)
        assertEquals(1f - 272f / 542f - .5f / 542f, quad[2].texture[5], .0001f)
        assertEquals(.5f + .5f / 960f, quad[3].texture[0], .0001f)
        assertTrue(quad[0].texture[1] > quad[2].texture[5])
    }

    @Test fun mixedAtlasUsesPerFeedSourceRectsWithoutAnotherDecoder() {
        val scene = VideoScene(1920f, 1080f, 2880, 1632, 4,
            (0..3).map { VideoWindow(it, it, 0f, 0f, 960f, 540f, it.toFloat()) },
            atlasColumns = 3, sourceCodedWidth = 1920, sourceCodedHeight = 1088,
            sourceDisplayWidth = 1920, sourceDisplayHeight = 1080,
            sourceRects = listOf(
                VideoSourceRect(0, 0, 1920, 1080),
                VideoSourceRect(1920, 0, 960, 540),
                VideoSourceRect(1920, 544, 960, 540),
                VideoSourceRect(1920, 1088, 960, 540),
            ))
        val quads = scene.quads()
        assertEquals(4, quads.size)
        assertEquals(1920f / 2880f + .5f / 2880, quads[1].texture[0], .0001f)
        assertEquals(1f - 544f / 1632f - .5f / 1632f, quads[2].texture[5], .0001f)
        assertEquals(1f - (544f + 540f) / 1632f + .5f / 1632f, quads[2].texture[1], .0001f)
        assertEquals(1f - (1088f + 540f) / 1632f + .5f / 1632f, quads[3].texture[1], .0001f)
    }

    @Test fun mixed1080AndTwo540RequiresFullAtlasCropRatherThanTemplateFeedCrop() {
        val windows = (0..2).map { VideoWindow(it, it, 0f, 0f, 960f, 540f, it.toFloat()) }
        val rects = listOf(
            VideoSourceRect(0, 0, 1920, 1080),
            VideoSourceRect(0, 1088, 960, 540),
            VideoSourceRect(960, 1088, 960, 540),
        )
        fun scene(displayHeight: Int) = VideoScene(2316f, 1080f, 1920, displayHeight, 3,
            windows, atlasColumns = 2, sourceCodedWidth = 1920, sourceCodedHeight = 1088,
            sourceDisplayWidth = 1920, sourceDisplayHeight = 1080,
            sourceRects = rects)
        try {
            scene(1624).quads()
            fail("Original 1624-pixel SPS crop must reject a 540p feed ending at pixel 1628")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("exceeds visible atlas 1920x1624"))
        }
        assertEquals(3, scene(1632).quads().size)
    }
}
