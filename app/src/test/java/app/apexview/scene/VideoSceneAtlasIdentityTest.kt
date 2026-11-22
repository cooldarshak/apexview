package app.apexview.scene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSceneAtlasIdentityTest {
    private fun scene(atlasWidth: Int = 1920, rects: List<VideoSourceRect> = listOf(
        VideoSourceRect(0, 0, 960, 540),
        VideoSourceRect(960, 0, 960, 540)
    ), windowX: Float = 0f) = VideoScene(
        width = 2316f, height = 1080f,
        atlasWidth = atlasWidth, atlasHeight = 1080, sources = 2,
        windows = listOf(
            VideoWindow(1, 0, windowX, 0f, 1500f, 1000f, 0f),
            VideoWindow(2, 1, 1500f, 0f, 816f, 1080f, 1f)
        ), atlasColumns = 2,
        sourceCodedWidth = atlasWidth / 2,
        sourceCodedHeight = 1080,
        sourceDisplayWidth = atlasWidth / 2,
        sourceDisplayHeight = 1080,
        sourceRects = rects, sourceFrameRate = 50.0
    )

    @Test fun normalLayoutMovementDoesNotDiscardCurrentDecodedPicture() {
        val tv = scene(windowX = 0f)
        val dragged = scene(windowX = 80f)
        assertTrue(tv.sameEncodedAtlasAs(dragged))
        assertTrue(dragged.sameEncodedAtlasAs(tv))
    }

    @Test fun newInputCropsMustWaitForFreshCodecFrame() {
        val initial = scene()
        val newRects = listOf(
            VideoSourceRect(0, 0, 960, 500),
            VideoSourceRect(960, 0, 960, 500)
        )
        assertFalse(initial.sameEncodedAtlasAs(scene(rects = newRects)))
    }

    @Test fun newCodedResolutionCannotReuseOldPicture() {
        val initial = scene()
        assertFalse(initial.sameEncodedAtlasAs(scene(atlasWidth = 3840)))
    }
}
