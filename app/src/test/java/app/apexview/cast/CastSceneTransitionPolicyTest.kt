package app.apexview.cast

import app.apexview.scene.VideoScene
import app.apexview.scene.VideoSourceRect
import app.apexview.scene.VideoWindow
import org.junit.Assert.*
import org.junit.Test

class CastSceneTransitionPolicyTest {
    private fun scene(width: Int, height: Int) = VideoScene(1920f, 1080f,
        width, height, 2, listOf(
            VideoWindow(11, 0, 0f, 0f, 1280f, 720f, 0f),
            VideoWindow(12, 1, 1280f, 0f, 640f, 360f, 1f)),
        1, 960, 540, 960, 540,
        listOf(
            VideoSourceRect(0, 0, 960, 540),
            VideoSourceRect(0, 540, 960, 540)))
    @Test fun acceptsNewPictureAndCropsForTheSameAuthorizedFeeds() {
        val prior = scene(1920, 2160)
        val next = prior.copy(atlasWidth = 1920, atlasHeight = 1080,
            sourceRects = listOf(
                VideoSourceRect(0, 0, 960, 540),
                VideoSourceRect(960, 0, 960, 540)))
        assertTrue(CastSceneTransitionPolicy.valid(prior, next, false))
        assertTrue(CastSceneTransitionPolicy.valid(prior, next.copy(
            atlasWidth = prior.atlasWidth, atlasHeight = prior.atlasHeight,
            sourceRects = prior.sourceRects), false) == false)
        assertThrows(IllegalArgumentException::class.java) {
            CastSceneTransitionPolicy.valid(prior, next, true)
        }
    }

    @Test fun refusesFeedSwapOrChangedDecoderCountWithoutNewPlay() {
        val original = scene(1920, 2160)
        val swap = original.copy(windows = original.windows.map {
            it.copy(source = 1 - it.source)
        })
        assertThrows(IllegalArgumentException::class.java) {
            CastSceneTransitionPolicy.valid(original, swap, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CastSceneTransitionPolicy.valid(original, original.copy(
                sources = 1, windows = original.windows.take(1)), false)
        }
    }

    @Test fun allowsWindowResizeWithoutReloadingEncodedPicture() {
        val original = scene(1920, 2160)
        val next = original.copy(windows = original.windows.map {
            it.copy(width = it.width * 0.9f)
        })
        assertFalse(CastSceneTransitionPolicy.valid(original, next, true))
    }
}
