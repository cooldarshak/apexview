package app.apexview.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneQuadBufferCacheTest {
    private fun scene(x: Float = 0f, feeds: Int = 2): VideoScene =
        VideoScene(
            width = 640f, height = 360f,
            atlasWidth = 640, atlasHeight = 180,
            sources = feeds,
            windows = (0 until feeds).map { index ->
                VideoWindow(index, index, x + index * 160f, 0f, 160f, 180f, index.toFloat())
            },
            atlasColumns = feeds,
            sourceCodedWidth = 640 / feeds,
            sourceCodedHeight = 180,
            sourceDisplayWidth = 640 / feeds,
            sourceDisplayHeight = 180,
        )

    @Test fun repeatedDragKeepsSameNativeBuffersButUpdatesVertexCoordinates() {
        val cache = SceneQuadBufferCache()
        val buffers = cache.update(scene())
        val firstCoordinate = buffers[0].first.get(0)
        val originalPosition = buffers[0].first
        val originalTexture = buffers[0].second
        for (i in 1..300) {
            val changed = cache.update(scene(x = i.toFloat() / 2))
            assertSame(originalPosition, changed[0].first)
            assertSame(originalTexture, changed[0].second)
        }
        assertEquals(4, cache.allocatedDirectBuffers)
        assertTrue(firstCoordinate != buffers[0].first.get(0))
        assertEquals(0, buffers[0].first.position())
        assertEquals(8, buffers[0].first.limit())
    }

    @Test fun feedCountChangesAllocateOnlyForNewWindows() {
        val cache = SceneQuadBufferCache()
        cache.update(scene(feeds = 1))
        assertEquals(2, cache.allocatedDirectBuffers)
        cache.update(scene(feeds = 2))
        assertEquals(4, cache.allocatedDirectBuffers)
        cache.update(scene(feeds = 1))
        assertEquals(4, cache.allocatedDirectBuffers)
        cache.update(scene(feeds = 2))
        assertEquals(6, cache.allocatedDirectBuffers)
    }
}
