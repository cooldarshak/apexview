package app.apexview.tiled

/** Keeps validation outside decoder ownership. Also covers cancellation during the handoff. */
internal class ArchiveCompositionStartup(
    private val stage: (String, Long) -> Unit = { _, _ -> },
    private val nowNs: () -> Long = System::nanoTime,
) {
    var sourceDecodersTouched = false
        private set
    var hostReplaced = false
        private set

    suspend fun <T> start(
        preflight: suspend () -> T,
        releaseSources: suspend () -> Unit,
        replaceHost: suspend (T) -> Unit,
        restoreSources: (Boolean) -> Unit,
        dispose: (T) -> Unit,
        validatePrepared: () -> Unit = {},
    ) {
        val started = nowNs()
        fun mark(name: String) = stage(name, ((nowNs() - started) / 1_000_000L).coerceAtLeast(0))
        suspend fun checkCurrent() {
            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.let { job ->
                if (!job.isActive) throw kotlinx.coroutines.CancellationException("Archive preflight cancelled")
            }
            validatePrepared()
        }
        mark("PREFLIGHT")
        val prepared = preflight() // Failure here must never change a playing source.
        try {
            mark("VALIDATED")
            checkCurrent()
            sourceDecodersTouched = true
            releaseSources()
            mark("SOURCES_RELEASED")
            checkCurrent()
            // Replacement can fail after setMediaSource. Roll it back in that case too.
            hostReplaced = true
            replaceHost(prepared)
            mark("HOST_INSTALLED")
        } catch (error: Throwable) {
            mark(if (error is kotlinx.coroutines.CancellationException) "SUPERSEDED" else "FAILED")
            if (sourceDecodersTouched) try { restoreSources(hostReplaced) }
            catch (restoreError: Throwable) { error.addSuppressed(restoreError) }
            throw error
        } finally {
            dispose(prepared)
        }
    }
}
