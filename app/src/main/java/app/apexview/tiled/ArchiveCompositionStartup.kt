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
        var primaryFailure: Throwable? = null
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
            primaryFailure = error
            // Diagnostics must not prevent rollback, and cleanup must not
            // replace the original cancellation/decoder failure.
            try { mark(if (error is kotlinx.coroutines.CancellationException) "SUPERSEDED" else "FAILED") }
            catch (diagnosticError: Throwable) { if (diagnosticError !== error) error.addSuppressed(diagnosticError) }
            if (sourceDecodersTouched) try { restoreSources(hostReplaced) }
            catch (restoreError: Throwable) { if (restoreError !== error) error.addSuppressed(restoreError) }
            throw error
        } finally {
            try { dispose(prepared) }
            catch (disposeError: Throwable) {
                val failure = primaryFailure
                if (failure == null) throw disposeError
                if (disposeError !== failure) failure.addSuppressed(disposeError)
            }
        }
    }
}

/** Independently retire archive-owned resources, preserving the primary error. */
internal class ArchiveRetirementSteps(primaryFailure: Throwable? = null) {
    private var failure: Throwable? = primaryFailure
    fun run(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
    }
    fun finish() { failure?.let { throw it } }
}
