package app.apexview.scene

import java.util.concurrent.atomic.AtomicLong

/** App-owned EGL objects, not driver allocations or SurfaceFlinger buffers.
 * Store scalar evidence only; never retain a View, Surface, texture or player.
 */
internal class SceneGraphicsOwners {
    data class Owner(
        val backend: String, val surfaceEpoch: Long, val readinessEpoch: Long,
        val contexts: Int, val windows: Int, val decoderTextures: Int,
        val atlasWidth: Int, val atlasHeight: Int, val outputWidth: Int, val outputHeight: Int,
    ) {
        val atlasPixels: Long get() = atlasWidth.toLong() * atlasHeight
        val outputPixels: Long get() = outputWidth.toLong() * outputHeight
    }
    private val next = AtomicLong()
    private val owners = linkedMapOf<Long, Owner>()
    fun newId(): Long = next.incrementAndGet()
    @Synchronized fun record(id: Long, owner: Owner) {
        require(owner.contexts in 0..1 && owner.windows >= 0 && owner.decoderTextures in 0..1)
        require(owner.atlasWidth >= 0 && owner.atlasHeight >= 0 && owner.outputWidth >= 0 && owner.outputHeight >= 0)
        if (owner.contexts == 0 && owner.windows == 0 && owner.decoderTextures == 0) owners.remove(id)
        else owners[id] = owner
    }
    @Synchronized fun remove(id: Long) { owners.remove(id) }
    @Synchronized fun snapshot(): Map<Long, Owner> = LinkedHashMap(owners)
    fun fields(): String {
        val live = snapshot()
        return " sceneGraphicsOwners=${live.size} sceneEglContexts=${live.values.sumOf { it.contexts }}" +
            " sceneEglWindows=${live.values.sumOf { it.windows }}" +
            " sceneDecoderTextures=${live.values.sumOf { it.decoderTextures }}" +
            " sceneAtlasPixels=${live.values.sumOf { it.atlasPixels }}" +
            " sceneOutputPixels=${live.values.sumOf { it.outputPixels }}" +
            " sceneGraphicsDetail=" + live.entries.joinToString(",") { (id, owner) ->
                "${id}:${owner.backend}:${owner.surfaceEpoch}:${owner.readinessEpoch}:" +
                    "${owner.contexts}:${owner.windows}:${owner.decoderTextures}:" +
                    "${owner.atlasWidth}x${owner.atlasHeight}:${owner.outputWidth}x${owner.outputHeight}"
            }.ifEmpty { "NONE" } + " sceneGraphicsBytes=NOT_MEASURED"
    }
    companion object { val process = SceneGraphicsOwners() }
}
