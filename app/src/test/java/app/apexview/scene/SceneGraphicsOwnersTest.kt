package app.apexview.scene

import org.junit.Assert.*
import org.junit.Test

class SceneGraphicsOwnersTest {
    @Test fun lifetimeEventsReportOnlyTransitionsAndReleaseExactlyOnce() {
        val events = mutableListOf<Triple<Long, SceneGraphicsOwners.Owner?, SceneGraphicsOwners.Owner?>>()
        val owners = SceneGraphicsOwners { id, before, after -> events.add(Triple(id, before, after)) }
        val id = owners.newId()
        val state = SceneGraphicsOwners.Owner("TextureView", 1, 1, 1, 1, 1, 1920, 2176, 2316, 1080)
        owners.record(id, state)
        owners.record(id, state)
        owners.record(id, state.copy(readinessEpoch = 2))
        owners.remove(id)
        owners.remove(id)
        assertEquals(3, events.size)
        assertNull(events.first().second)
        assertEquals(2L, events[1].third!!.readinessEpoch)
        assertNull(events.last().third)
    }

    @Test fun queueSurvivesTextureRetirementAndEncoderIsDistinct() {
        val owners = SceneGraphicsOwners()
        val queue = owners.newId()
        val encoder = owners.newId()
        val state = SceneGraphicsOwners.Owner("CastInput", 1, 1, 0, 0, 0, 0, 0, 0, 0,
            captureTextures = 1, captureQueues = 1, captureWidth = 1280, captureHeight = 720)
        owners.record(queue, state)
        owners.record(encoder, state.copy(backend = "Encoder", windows = 1,
            captureTextures = 0, captureQueues = 0, encoderInputs = 1))
        owners.record(queue, state.copy(captureTextures = 0))
        assertEquals(2, owners.snapshot().size)
        assertTrue(owners.fields().contains("sceneCaptureQueues=1"))
        assertTrue(owners.fields().contains("sceneEncoderInputs=1"))
        owners.record(queue, state.copy(captureTextures = 0, captureQueues = 0))
        assertEquals(setOf(encoder), owners.snapshot().keys)
        owners.remove(encoder)
        assertTrue(owners.snapshot().isEmpty())
    }

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
        assertTrue(owners.fields().contains("scenePeakOwners=2"))
        assertTrue(owners.fields().contains("scenePeakEglContexts=2"))
        assertTrue(owners.fields().contains("scenePeakDecoderTextures=2"))
        assertTrue(owners.fields().contains("scenePeakScope=PROCESS_LIFETIME"))
    }
}
