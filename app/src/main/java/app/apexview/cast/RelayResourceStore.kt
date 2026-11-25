package app.apexview.cast

import java.io.OutputStream
import java.util.IdentityHashMap

/** Bounded immutable relay buffers, retained across rolling publication by identity.
 * No socket I/O under the publisher lock. Retired ciphertext/clear bytes are
 * erased only after the final read lease ends. No sample transformation. */
internal class RelayResourceStore(
    private val maxBytes: Long = app.apexview.tiled.ProtectedOutputHistoryPolicy.MAX_RETAINED_BYTES + 32L * 1024 * 1024,
    private val maxResources: Int = 64,
    private val maxReaders: Int = 3,
) : AutoCloseable {
    init { require(maxBytes > 0 && maxResources > 0 && maxReaders > 0) }
    private class Entry(val heap: ByteArray?, val identity: Any, val release: () -> Unit,
                        val native: app.apexview.tiled.NativeFragmentLease? = null) {
        init { require((heap == null) != (native == null)) }
        val size: Int get() = heap?.size ?: requireNotNull(native).size
        val backing: Any get() = heap ?: requireNotNull(native).storageIdentity
        var current = true
        var readers = 0
    }
    private var current = emptyMap<String, Entry>()
    private val pinned = mutableSetOf<Entry>()
    private var readers = 0
    private var pinnedBytes = 0L
    private var closed = false

    class Lease internal constructor(private val heap: ByteArray?, private val release: () -> Unit,
                                     private val native: app.apexview.tiled.NativeFragmentLease? = null) : AutoCloseable {
        val size = heap?.size ?: requireNotNull(native).size
        private var closed = false
        @Synchronized fun writeTo(output: OutputStream, start: Int, count: Int) {
            check(!closed)
            require(start >= 0 && count >= 0 && start <= size - count)
            if (heap != null) output.write(heap, start, count)
            else requireNotNull(native).writeTo(output, start, count)
        }
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            release()
        }
    }
    data class Snapshot(val currentBytes: Long, val pinnedBytes: Long, val readers: Int)
    @Synchronized fun snapshot() = Snapshot(current.values.sumOf { it.size.toLong() }, pinnedBytes, readers)

    @Synchronized fun publish(
        next: Map<String, ByteArray>,
        shared: Map<String, SharedRelayResource> = emptyMap(),
        native: Map<String, app.apexview.tiled.NativeFragmentLease> = emptyMap(),
    ) {
        check(!closed)
        require(next.size.toLong() + shared.size + native.size <= maxResources &&
            next.values.sumOf { it.size.toLong() } + shared.values.sumOf { it.size.toLong() } +
                native.values.sumOf { it.size.toLong() } <= maxBytes) {
            "Receiver media window exceeds its bounded memory budget"
        }
        val existing = IdentityHashMap<Any, Entry>()
        current.values.forEach { existing[it.identity] = it }
        val retired = IdentityHashMap<Any, Entry>()
        pinned.filterNot { it.current }.forEach { retired[it.identity] = it }
        val backingOwners = IdentityHashMap<Any, Any>()
        current.values.forEach { backingOwners[it.backing] = it.identity }
        pinned.forEach { backingOwners[it.backing] = it.identity }
        val seen = IdentityHashMap<Any, Boolean>()
        val replacement = LinkedHashMap<String, Entry>(next.size + shared.size + native.size)
        val acquired = mutableListOf<SharedRelayResource.Retained>()
        val redundantNative = mutableListOf<app.apexview.tiled.NativeFragmentLease>()
        fun validate(name: String, identity: Any, backing: Any) {
            val currentOwner = backingOwners[backing]
            require(name.matches(resourceName) && name != "." && name != ".." &&
                name !in replacement && seen.put(backing, true) == null &&
                (currentOwner == null || currentOwner === identity)) {
                "Invalid or aliased relay resource"
            }
        }
        try {
            next.forEach { (name, bytes) ->
                validate(name, bytes, bytes)
                require(bytes.isNotEmpty() && !retired.containsKey(bytes)) { "Invalid or previously retired relay resource" }
                replacement[name] = existing[bytes] ?: Entry(bytes, bytes, { bytes.fill(0) })
            }
            shared.forEach { (name, resource) ->
                validate(name, resource, resource.bufferIdentity)
                replacement[name] = existing[resource] ?: retired[resource] ?: run {
                    val handle = resource.retainForRelay()
                    acquired.add(handle)
                    Entry(handle.bytes, resource, handle.release)
                }
            }
            native.forEach { (name, lease) ->
                val identity = lease.storageIdentity
                validate(name, identity, identity)
                val previous = existing[identity] ?: retired[identity]
                if (previous != null) {
                    // A rolling window may include a separately retained token
                    // for an already-published immutable native allocation.
                    require(previous.native !== lease && previous.size == lease.size)
                    redundantNative += lease
                    replacement[name] = previous
                } else {
                    replacement[name] = Entry(null, identity, { lease.close() }, lease)
                }
            }
        } catch (failure: Throwable) {
            acquired.forEach { it.release() }
            throw failure
        }
        val retained = replacement.values.toSet()
        val previous = current
        retained.forEach { it.current = true }
        current = replacement
        previous.values.filterNot { it in retained }.forEach(::retire)
        redundantNative.forEach { it.close() }
    }

    @Synchronized fun acquire(name: String): Lease? {
        val entry = current[name] ?: return null
        if (readers >= maxReaders || (entry.readers == 0 && entry.size.toLong() > maxBytes - pinnedBytes))
            return null
        if (entry.readers++ == 0) { pinned.add(entry); pinnedBytes += entry.size }
        readers++
        return Lease(entry.heap, {
            synchronized(this) {
                check(entry.readers > 0 && readers > 0)
                readers--
                if (--entry.readers == 0) {
                    pinned.remove(entry)
                    pinnedBytes -= entry.size
                    if (!entry.current) entry.release()
                }
            }
        }, entry.native)
    }

    private fun retire(entry: Entry) {
        entry.current = false
        if (entry.readers == 0) entry.release()
    }

    private val resourceName = Regex("[a-zA-Z0-9.-]{1,80}")

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val previous = current
        current = emptyMap()
        previous.values.forEach(::retire)
    }
}
