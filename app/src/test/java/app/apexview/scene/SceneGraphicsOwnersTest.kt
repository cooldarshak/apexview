package app.apexview.scene

import org.junit.Assert.*
import org.junit.Test

class SceneGraphicsOwnersTest {
    @Test fun replacedOwnerIsNotCountedTwiceAndRetiredOwnerIsRemoved() {
        val owners = SceneGraphicsOwners()
        val a = owners.newId()
        val b = owners.newId()
        val scene = SceneGraphicsOwners.Owner("TextureView", 1, 2, 1, 1, 1, 1920, 1088, 1920, 1080)
        owners.record(a, scene)
        owners.record(a, scene.copy(readinessEpoch = 3, atlasWidth = 3840))
        owners.record(b, scene.copy(backend = "SurfaceView", surfaceEpoch = 2))
        assertEquals(2, owners.snapshot().size)
        assertEquals(2, owners.snapshot().values.sumOf { it.decoderTextures })
        assertEquals(3840L * 1088L + 1920L * 1088L, owners.snapshot().values.sumOf { it.atlasPixels })
        owners.remove(a)
        assertEquals(setOf(b), owners.snapshot().keys)
        assertTrue(owners.fields().contains("sceneGraphicsBytes=NOT_MEASURED"))
        owners.record(b, scene.copy(contexts = 0, windows = 0, decoderTextures = 0))
        assertTrue(owners.snapshot().isEmpty())
        assertTrue(owners.fields().contains("sceneGraphicsDetail=NONE"))
    }
}
