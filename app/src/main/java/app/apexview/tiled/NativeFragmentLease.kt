package app.apexview.tiled

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One private direct view of a bounded Rust allocation. The writer fills the
 * buffer synchronously, then only bounded read/inspection is exposed. Each
 * history, detached window or resource-store owner receives its own idempotent
 * lease. The final release drops the private view before returning memory to
 * Rust; neither callers nor Media3 receive a raw native pointer or view.
 */
internal class NativeFragmentLease private constructor(private val shared: Shared) : FragmentReadLease {
    private class Shared(val id: Long, var buffer: ByteBuffer?, val size: Int) {
        var owners = 1
    }
    private var closed = false
    override val size: Int get() = shared.size
    override val storageIdentity: Any get() = shared
    override val nativeBacked get() = true

    @Synchronized fun retain(): NativeFragmentLease {
        check(!closed)
        synchronized(shared) {
            check(shared.buffer != null && shared.owners > 0)
            shared.owners = Math.addExact(shared.owners, 1)
        }
        return NativeFragmentLease(shared)
    }

    @Synchronized override fun readTo(target: ByteArray, offset: Int, start: Int, count: Int) {
        check(!closed)
        require(start >= 0 && count >= 0 && start <= size-count &&
            offset >= 0 && offset <= target.size-count)
        synchronized(shared) {
            // Private immutable backing, cursor protected by the shared
            // monitor. Avoid a new ByteBuffer wrapper for every Media3 read.
            val b = requireNotNull(shared.buffer)
            b.position(start); b.get(target, offset, count)
        }
    }

    @Synchronized fun inspect(priorInitialization: CmafBoxInspector.Report): CmafBoxInspector.Report {
        check(!closed)
        return synchronized(shared) {
            CmafBoxInspector.inspect(requireNotNull(shared.buffer).asReadOnlyBuffer().apply { position(0) }, priorInitialization)
        }
    }

    /** Only a bounded transfer scratch buffer is needed for HTTP/TLS.
     * Each socket reader holds its independent lease until the write ends. */
    @Synchronized override fun writeTo(output: java.io.OutputStream, start: Int, count: Int) {
        check(!closed)
        require(start >= 0 && count >= 0 && start <= size - count)
        if (count == 0) return
        val scratch = ByteArray(minOf(count, 32 * 1024))
        try {
            var sent = 0
            while (sent < count) {
                val n = minOf(scratch.size, count - sent)
                readTo(scratch, 0, start + sent, n)
                output.write(scratch, 0, n)
                sent += n
            }
        } finally { scratch.fill(0) }
    }

    @Synchronized fun matches(bytes: ByteArray): Boolean {
        check(!closed)
        if (bytes.size != size) return false
        return synchronized(shared) {
            requireNotNull(shared.buffer).asReadOnlyBuffer().apply { position(0) } == ByteBuffer.wrap(bytes)
        }
    }

    fun copyBytes(): ByteArray = ByteArray(size).also { readTo(it, 0, 0, it.size) }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        synchronized(shared) {
            check(shared.owners > 0)
            if (--shared.owners == 0) {
                shared.buffer = null
                check(NativeStitchingEngine.releaseFragment(shared.id) == 1L)
            }
        }
    }

    companion object {
        /** Only admission failures return null. A writer/validation failure
         * releases the unpublishable allocation and propagates unchanged. */
        fun build(size: Int, writer: (ByteBuffer) -> Unit): NativeFragmentLease? =
            build(size, null, writer)

        fun build(size: Int, context: android.content.Context?, writer: (ByteBuffer) -> Unit): NativeFragmentLease? =
            buildOwned(size, context) { _, buffer -> writer(buffer) }

        fun fromEngine(size: Int, context: android.content.Context?, engine: NativeStitchingEngine,
                       token: Long, offset: Int): NativeFragmentLease? =
            buildOwned(size, context, beforeView = { id ->
                check(engine.readToFragment(token, offset, id, size) == size.toLong())
            }) { _, buffer -> buffer.position(size) }

        private fun buildOwned(size: Int, context: android.content.Context?,
                               beforeView: ((Long) -> Unit)? = null,
                               writer: (Long, ByteBuffer) -> Unit): NativeFragmentLease? {
            if (size <= 0) return null
            if (size > 64 * 1024 * 1024) return null
            val id = try {
                NativeStitchingDeviceBudget.admit {
                    if (context == null) NativeStitchingEngine.allocateFragment(size)
                    else {
                        val h = NativeStitchingDeviceBudget.sample(context)
                        if (h == null || h.lowMemory || h.available <= h.threshold) NativeStitchingEngine.BACKPRESSURE
                        else NativeStitchingEngine.allocateFragmentHeadroom(size,
                            (h.available - h.threshold) / 8)
                    }
                }
            }
                catch (_: RuntimeException) { return null }
                catch (_: LinkageError) { return null }
            if (id <= 0) return null
            var transferred = false
            try {
                beforeView?.invoke(id)
                val buffer = NativeStitchingEngine.fragmentView(id) ?: return null
                require(buffer.isDirect && !buffer.isReadOnly && buffer.capacity() == size)
                buffer.clear()
                buffer.order(ByteOrder.BIG_ENDIAN)
                writer(id, buffer)
                check(buffer.position() == size)
                buffer.flip()
                val lease = NativeFragmentLease(Shared(id, buffer.asReadOnlyBuffer(), size))
                transferred = true
                return lease
            } finally {
                if (!transferred) NativeStitchingEngine.releaseFragment(id)
            }
        }

        fun trim() {
            if (!NativeStitchingEngine.isLibraryReady()) return
            try {
                val before = NativeStitchingEngine.engineMemorySnapshot()?.getOrNull(2) ?: -1L
                val fragments = NativeStitchingEngine.fragmentMemorySnapshot()?.getOrNull(0) ?: -1L
                NativeStitchingEngine.trimFragments()
                val after = NativeStitchingEngine.engineMemorySnapshot()?.getOrNull(2) ?: -1L
                val retained = NativeStitchingEngine.fragmentMemorySnapshot()?.getOrNull(0) ?: -1L
                android.util.Log.d("ApexviewSingleDecoder", "GC_NATIVE_TRIM payloadBefore=$before payloadAfter=$after fragmentCapacityBefore=$fragments fragmentCapacityAfter=$retained")
            } catch (_: LinkageError) { }
        }
    }
}
