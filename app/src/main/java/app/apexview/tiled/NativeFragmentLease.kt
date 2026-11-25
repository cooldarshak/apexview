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
internal class NativeFragmentLease private constructor(private val shared: Shared) : AutoCloseable {
    private class Shared(val id: Long, var buffer: ByteBuffer?, val size: Int) {
        var owners = 1
    }
    private var closed = false
    val size: Int get() = shared.size

    @Synchronized fun retain(): NativeFragmentLease {
        check(!closed)
        synchronized(shared) {
            check(shared.buffer != null && shared.owners > 0)
            shared.owners = Math.addExact(shared.owners, 1)
        }
        return NativeFragmentLease(shared)
    }

    @Synchronized fun readTo(target: ByteArray, offset: Int, start: Int, count: Int) {
        check(!closed)
        require(start >= 0 && count >= 0 && start <= size-count &&
            offset >= 0 && offset <= target.size-count)
        synchronized(shared) {
            val b = requireNotNull(shared.buffer).asReadOnlyBuffer()
            b.position(start); b.get(target, offset, count)
        }
    }

    @Synchronized fun inspect(priorInitialization: CmafBoxInspector.Report): CmafBoxInspector.Report {
        check(!closed)
        return synchronized(shared) {
            CmafBoxInspector.inspect(requireNotNull(shared.buffer).asReadOnlyBuffer(), priorInitialization)
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
        fun build(size: Int, writer: (ByteBuffer) -> Unit): NativeFragmentLease? {
            if (size <= 0) return null
            val id = try { NativeStitchingEngine.allocateFragment(size) }
                catch (_: RuntimeException) { return null }
                catch (_: LinkageError) { return null }
            if (id <= 0) return null
            var transferred = false
            try {
                val buffer = NativeStitchingEngine.fragmentView(id) ?: return null
                require(buffer.isDirect && !buffer.isReadOnly && buffer.capacity() == size)
                buffer.clear()
                buffer.order(ByteOrder.BIG_ENDIAN)
                writer(buffer)
                check(buffer.position() == size)
                buffer.flip()
                val lease = NativeFragmentLease(Shared(id, buffer, size))
                transferred = true
                return lease
            } finally {
                if (!transferred) NativeStitchingEngine.releaseFragment(id)
            }
        }

        fun trim() {
            try { NativeStitchingEngine.trimFragments() } catch (_: LinkageError) { }
        }
    }
}
