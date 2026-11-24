package app.apexview.scene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneSurfaceLifetimeTest {
    @Test fun queuedDecoderAttachCannotOutliveReplacedSurface() {
        val lifetime = SceneSurfaceLifetime()
        val first = lifetime.advance()
        assertTrue(lifetime.matches(first))
        lifetime.advance() // Android surfaceDestroyed
        assertFalse(lifetime.matches(first))
        val next = lifetime.advance() // newly created SurfaceView or TextureView
        assertTrue(lifetime.matches(next))
        assertFalse(lifetime.matches(first))
        lifetime.advance() // release
        assertFalse(lifetime.matches(next))
    }

    @Test fun codedAtlasChangeDoesNotChangeSurfaceLifetime() {
        val lifetime = SceneSurfaceLifetime()
        val output = lifetime.advance()
        // Only decoded metadata, crop rectangles and output geometry changed.
        assertTrue(lifetime.matches(output))
        assertTrue(lifetime.matches(lifetime.generation()))
    }
}
