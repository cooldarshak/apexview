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
    var allocatedDirectBuffers = 0
        private set

    private fun allocate(): FloatBuffer {
        allocatedDirectBuffers++
        return ByteBuffer.allocateDirect(8 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    fun update(model: VideoScene): List<Pair<FloatBuffer, FloatBuffer>> {
        val quads = model.quads()
        while (pairs.size > quads.size) pairs.removeAt(pairs.lastIndex)
        while (pairs.size < quads.size) pairs.add(allocate() to allocate())
        quads.forEachIndexed { index, quad ->
            val (positions, texcoords) = pairs[index]
            positions.clear()
            positions.put(quad.positions)
            positions.flip()
            texcoords.clear()
            texcoords.put(quad.texture)
            texcoords.flip()
        }
        return pairs
    }
}
