package app.apexview.cast

/** One budget for all source-owned audio/text in a Cast load, including retired relay readers. */
internal class RelayMediaBudget(private val maxBytes: Long = 32L * 1024 * 1024) {
    private var retainedBytes = 0L

    @Synchronized fun own(bytes: ByteArray): SharedRelayResource {
        require(bytes.isNotEmpty() && bytes.size.toLong() <= maxBytes - retainedBytes) {
            "Cast audio and text exceed their retained-byte budget"
        }
        retainedBytes += bytes.size
        recordMemory()
        return SharedRelayResource(bytes) {
            synchronized(this) { retainedBytes -= bytes.size; recordMemory() }
        }
    }

    private fun recordMemory() = app.apexview.MediaMemoryAccounting.process.record(this,
        app.apexview.MediaMemoryAccounting.Kind.SIDECAR, retainedBytes)
    @Synchronized fun bytesInUse() = retainedBytes
}

/** A producer and a relay can own the same validated, immutable backing buffer. */
internal class SharedRelayResource(
    private val bytes: ByteArray,
    private val onErased: () -> Unit,
) : AutoCloseable {
    val size: Int get() = bytes.size
    val bufferIdentity: Any get() = bytes
    private var relayOwners = 0
    private var producerClosed = false

    data class Retained(val bytes: ByteArray, val release: () -> Unit)

    @Synchronized fun retainForRelay(): Retained {
        check(!producerClosed)
        relayOwners++
        return Retained(bytes, ::releaseRelay)
    }

    @Synchronized private fun releaseRelay() {
        check(relayOwners > 0)
        relayOwners--
        eraseIfUnused()
    }

    @Synchronized override fun close() {
        if (producerClosed) return
        producerClosed = true
        eraseIfUnused()
    }

    private fun eraseIfUnused() {
        if (!producerClosed || relayOwners != 0) return
        bytes.fill(0)
        onErased()
    }
}
