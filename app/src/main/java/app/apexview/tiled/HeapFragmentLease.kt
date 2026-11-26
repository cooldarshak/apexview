package app.apexview.tiled

import java.io.OutputStream

/** Common read-only publication boundary; storage type is explicit. */
internal interface FragmentReadLease : AutoCloseable {
    val size: Int
    val storageIdentity: Any
    val nativeBacked: Boolean
    fun readTo(target: ByteArray, offset: Int, start: Int, count: Int)
    fun writeTo(output: OutputStream, start: Int, count: Int)
}

/** Transfers an already validated, exclusively owned heap array without
 * cloning it. History, publisher and slow readers retire independently. */
internal class HeapFragmentLease private constructor(private val shared: Shared) : FragmentReadLease {
    private class Shared(val bytes: ByteArray) {
        var owners = 1
        init { app.apexview.MediaMemoryAccounting.process.record(this,
            app.apexview.MediaMemoryAccounting.Kind.SHARED_FRAGMENT, bytes.size.toLong()) }
    }
    private var closed = false
    override val size get() = shared.bytes.size
    override val storageIdentity: Any get() = shared
    override val nativeBacked get() = false
    @Synchronized fun retain(): HeapFragmentLease {
        check(!closed)
        synchronized(shared) { shared.owners = Math.addExact(shared.owners, 1) }
        return HeapFragmentLease(shared)
    }
    @Synchronized override fun readTo(target: ByteArray, offset: Int, start: Int, count: Int) {
        check(!closed)
        require(start >= 0 && count >= 0 && start <= size-count && offset >= 0 && offset <= target.size-count)
        shared.bytes.copyInto(target, offset, start, start+count)
    }
    @Synchronized override fun writeTo(output: OutputStream, start: Int, count: Int) {
        check(!closed)
        require(start >= 0 && count >= 0 && start <= size-count)
        output.write(shared.bytes, start, count)
    }
    @Synchronized fun inspect(init: CmafBoxInspector.Report): CmafBoxInspector.Report {
        check(!closed)
        return CmafBoxInspector.inspect(shared.bytes, init)
    }
    @Synchronized fun matches(bytes: ByteArray): Boolean { check(!closed); return shared.bytes.contentEquals(bytes) }
    @Synchronized fun copyBytes(): ByteArray { check(!closed); return shared.bytes.copyOf() }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        synchronized(shared) {
            if (--shared.owners == 0) {
                shared.bytes.fill(0)
                app.apexview.MediaMemoryAccounting.process.record(shared,
                    app.apexview.MediaMemoryAccounting.Kind.SHARED_FRAGMENT, 0)
            }
        }
    }
    companion object {
        fun take(bytes: ByteArray): HeapFragmentLease {
            require(bytes.isNotEmpty())
            return HeapFragmentLease(Shared(bytes))
        }
    }
}
