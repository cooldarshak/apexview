package app.apexview.tiled

/** Keeps validation outside decoder ownership. Also covers cancellation during the handoff. */
internal class ArchiveCompositionStartup {
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
    ) {
        val prepared = preflight() // Failure here must never change a playing source.
        try {
            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.let { job ->
                if (!job.isActive) throw kotlinx.coroutines.CancellationException("Archive preflight cancelled")
            }
            sourceDecodersTouched = true
            releaseSources()
            // Replacement can fail after setMediaSource. Roll it back in that case too.
            hostReplaced = true
            replaceHost(prepared)
        } catch (error: Throwable) {
            if (sourceDecodersTouched) try { restoreSources(hostReplaced) }
            catch (restoreError: Throwable) { error.addSuppressed(restoreError) }
            throw error
        } finally {
            dispose(prepared)
        }
    }
}
