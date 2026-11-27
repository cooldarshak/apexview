package app.apexview.scene

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GL-looper-owned buffers for all local and Cast scene renderers. Layout
 * transitions rewrite geometry; they never copy or read back decoded frames.
 */
internal class SceneQuadBufferCache {
    private val pairs = ArrayList<Pair<FloatBuffer, FloatBuffer>>()
    private val active = ArrayList<Pair<FloatBuffer, FloatBuffer>>()
    // The app supports at most 24 selected feeds; do not retain arbitrary
    // larger caller geometries. Re-expanding a normal layout reuses its buffers.
    private companion object { const val MAX_RETAINED_WINDOWS = 24 }
    var allocatedDirectBuffers = 0
        private set

    private fun allocate(): FloatBuffer {
        allocatedDirectBuffers++
        return ByteBuffer.allocateDirect(8 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    fun clear() {
        active.clear()
        pairs.clear()
    }

    fun update(model: VideoScene): List<Pair<FloatBuffer, FloatBuffer>> {
        val quads = model.quads()
        val retainedCount = minOf(quads.size, MAX_RETAINED_WINDOWS)
        while (pairs.size < retainedCount) pairs.add(allocate() to allocate())
        active.clear()
        quads.forEachIndexed { index, quad ->
            val pair = pairs.getOrNull(index) ?: (allocate() to allocate())
            active.add(pair)
            val (positions, texcoords) = pair
            positions.clear()
            positions.put(quad.positions)
            positions.flip()
            texcoords.clear()
            texcoords.put(quad.texture)
            texcoords.flip()
        }
        return active
    }
}
