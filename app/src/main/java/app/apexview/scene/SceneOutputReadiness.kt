package app.apexview.scene

/**
 * Scene-surface epoch gate. A queued GL callback from an old SurfaceTexture/EGL
 * surface must never mark a newly recreated or newly mapped scene as visible.
 * This gate deliberately means *successful EGL output submission*, not a
 * SurfaceFlinger physical presentation acknowledgement.
 */
internal class SceneOutputReadiness {
    private var generation = 0L
    private var ready = false

    @Synchronized fun invalidate(): Long {
        generation += 1L
        ready = false
        return generation
    }

    @Synchronized fun generation(): Long = generation

    /** True only for the first successfully submitted frame of the current surface. */
    @Synchronized fun acknowledgeSubmittedFrame(submittedGeneration: Long): Boolean {
        if (submittedGeneration != generation || ready) return false
        ready = true
        return true
    }

    @Synchronized fun matches(candidate: Long): Boolean = candidate == generation

    @Synchronized fun isReady(): Boolean = ready
}
