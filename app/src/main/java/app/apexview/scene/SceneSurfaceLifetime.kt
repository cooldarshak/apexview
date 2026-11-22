package app.apexview.scene

/**
 * Tracks only Android output Surface/SurfaceTexture lifetimes, independently
 * of a new encoded atlas. A surface may be destroyed while GL initialization
 * or ExoPlayer.setVideoSurface is queued to another thread.
 */
internal class SceneSurfaceLifetime {
    private var epoch = 0L

    @Synchronized fun advance(): Long = ++epoch
    @Synchronized fun generation(): Long = epoch
    @Synchronized fun matches(candidate: Long): Boolean = candidate == epoch
}
