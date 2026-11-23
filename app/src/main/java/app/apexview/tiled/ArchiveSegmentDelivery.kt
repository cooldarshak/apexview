package app.apexview.tiled

/**
 * Shared foreground/prefetch delivery path for clear archive HEVC.
 * Cache ownership is protected by cacheLock; the single-flight coordinator never holds it
 * during network capture or CMAF composition. No secondary decoder is created here.
 */
internal class ArchiveSegmentDelivery(
    private val cache: BoundedArchiveFragmentCache,
    private val cacheLock: Any,
    private val production: ArchiveSegmentSingleFlight,
    private val checkOpen: () -> Unit,
    private val composeAndPublish: (Int) -> Unit,
    private val onCancelledWarmupRecovery: (Int) -> Unit = {},
    private val onEvictionRace: (Int, Int) -> Unit = { _, _ -> },
) {
    companion object {
        // One initial publication plus two retries. Never recompose forever under cache churn.
        const val MAX_EVICTION_RETRIES = 2
        const val MAX_CANCELLED_PREFETCH_RECOVERIES = 1
    }

    private fun readCopy(number: Int): ByteArray? = synchronized(cacheLock) {
        checkOpen()
        cache.readCopy(number)
    }

    private fun contains(number: Int): Boolean = synchronized(cacheLock) {
        checkOpen()
        cache.contains(number)
    }

    fun obtain(number: Int, copyForCaller: Boolean): ByteArray? {
        var evictionRetries = 0
        var cancelledRecoveries = 0
        while (true) {
            if (copyForCaller) {
                readCopy(number)?.let { return it }
            } else if (contains(number)) {
                return null // Prefetch never makes a second full-sized read copy.
            }

            try {
                production.run(number) {
                    if (!contains(number)) composeAndPublish(number)
                }
            } catch (cancelled: ArchiveWarmupCancelledException) {
                if (!copyForCaller || Thread.currentThread().isInterrupted ||
                    ++cancelledRecoveries > MAX_CANCELLED_PREFETCH_RECOVERIES) throw cancelled
                onCancelledWarmupRecovery(number)
                continue
            }

            // Copy under lock immediately after shared production. Another group may have
            // evicted this segment, but a borrowed array must never contain wiped ciphertext.
            if (copyForCaller) {
                readCopy(number)?.let { return it }
            } else if (contains(number)) {
                return null
            }
            if (evictionRetries >= MAX_EVICTION_RETRIES) {
                throw ArchiveVodMediaException(
                    "Archive segment $number repeatedly evicted during delivery",
                    retryable = copyForCaller
                )
            }
            evictionRetries++
            onEvictionRace(number, evictionRetries)
        }
    }
}

internal enum class ArchiveWarmupStopReason { CANCELLED, FAILED }

/** Classify without logging exception messages, URLs, tokens or Widevine information. */
internal object ArchiveWarmupFailurePolicy {
    fun classify(error: Exception, closed: Boolean, interrupted: Boolean): ArchiveWarmupStopReason {
        if (closed || interrupted) return ArchiveWarmupStopReason.CANCELLED
        val causes = generateSequence(error as Throwable) { it.cause }.take(8).toList()
        // SocketTimeoutException inherits InterruptedIOException, but indicates a real
        // transport failure and must remain visible to recovery and diagnostics.
        if (causes.any { it is java.net.SocketTimeoutException }) return ArchiveWarmupStopReason.FAILED
        return if (causes.any { it is ArchiveWarmupCancelledException ||
                it is InterruptedException || it is java.util.concurrent.CancellationException ||
                it is java.io.InterruptedIOException }) ArchiveWarmupStopReason.CANCELLED
        else ArchiveWarmupStopReason.FAILED
    }
}
