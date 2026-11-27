package app.apexview.cast

/** One worker owns both clocks. The entire batch is returned to the queue or
 * wiped and rolled back; a failed later network read cannot silently lose
 * the already extracted earlier commentary in that batch. */
internal class BakedAudioReadProgress(startUs: Long) {
    var nextAudioUs = startUs
    var lastLoadedSegmentStartUs = Long.MIN_VALUE

    fun transaction(read: (MutableList<BakedCastTransportStream.Sample>) -> Unit): List<BakedCastTransportStream.Sample> {
        val savedNext = nextAudioUs
        val savedSegment = lastLoadedSegmentStartUs
        val output = mutableListOf<BakedCastTransportStream.Sample>()
        try {
            read(output)
            return output
        } catch (failure: Throwable) {
            nextAudioUs = savedNext
            lastLoadedSegmentStartUs = savedSegment
            output.forEach { it.bytes.fill(0) }
            throw failure
        }
    }
}

/** Retry only transient network failures, not authorization, validation,
 * malformed media or cancellation. Bounded queue limits remain authoritative. */
internal object BakedAudioRecovery {
    fun retryDelayMs(failure: Throwable, consecutiveFailures: Int): Long? {
        if (consecutiveFailures !in 0..2 || Thread.currentThread().isInterrupted) return null
        var cause: Throwable? = failure
        repeat(12) {
            val current = cause ?: return null
            if (current is kotlinx.coroutines.CancellationException) return null
            if (current is java.net.UnknownHostException || current is java.net.SocketTimeoutException ||
                current is java.net.ConnectException) return 500L shl consecutiveFailures
            if (current is java.io.InterruptedIOException) return null
            cause = current.cause
        }
        return null
    }
}

/** HLS segment start times are ordered. Search the committed cursor directly
 * instead of scanning an entire completed archive on every audio wakeup.
 * The caller still validates the selected segment and its time extent. */
internal inline fun firstUnloadedAudioSegment(count: Int, committedStartUs: Long,
                                             startAt: (Int) -> Long): Int {
    var low = 0
    var high = count
    while (low < high) {
        val mid = low + (high - low) / 2
        if (startAt(mid) <= committedStartUs) low = mid + 1 else high = mid
    }
    return low
}
