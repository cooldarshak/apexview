package app.apexview.cast

import app.apexview.scene.VideoScene

/** The receiver accepts new authenticated source geometry without changing feed identity. */
internal object CastSceneTransitionPolicy {
    /**
     * A sender may move/resize/reorder windows, or publish a new encoded
     * atlas when a rolling HEVC rendition changes quality. The existing
     * decoder handles Media3 track format changes; no extra player is used.
     * Any unexpected substitution of a source identity fails closed.
     */
    fun valid(previous: VideoScene, next: VideoScene, direct: Boolean): Boolean {
        next.quads() // Bounds, crop validity, unique window IDs, finite layout.
        require(previous.sources == next.sources) {
            "Changing the selected feed count requires a new authorized playback"
        }
        val priorMapping = previous.windows.associate { it.id to it.source }
        val nextMapping = next.windows.associate { it.id to it.source }
        require(priorMapping == nextMapping) {
            "Cast source identities changed without a new authorized playback"
        }
        require(!direct || next.sameEncodedAtlasAs(previous)) {
            "Direct playback picture geometry is owned by Media3, not scene messages"
        }
        return !next.sameEncodedAtlasAs(previous)
    }
}

/** Baked capture is tied to the installed archive, not merely the requested
 * height. Wait for a different validated source and its one active decoder. */
internal object BakedQualityTransitionPolicy {
    fun canRestart(
        previousArchive: Any,
        installedArchive: Any?,
        preparing: Boolean,
        firstFrame: Boolean,
        activeDecoders: Int,
        playing: Boolean,
    ): Boolean = !preparing && installedArchive != null && installedArchive !== previousArchive &&
        firstFrame && activeDecoders == 1 && playing
}
