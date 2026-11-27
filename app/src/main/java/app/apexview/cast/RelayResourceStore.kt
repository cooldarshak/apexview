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
                        val native: app.apexview.tiled.FragmentReadLease? = null) {
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
                                     private val native: app.apexview.tiled.FragmentReadLease? = null) : AutoCloseable {
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
    data class Snapshot(val currentBytes: Long, val pinnedBytes: Long, val readers: Int,
                        val retiredPinnedBytes: Long, val exclusiveHeapBytes: Long,
                        val sharedHeapBytes: Long, val nativeLogicalBytes: Long) {
        val uniqueBytes get() = currentBytes + retiredPinnedBytes
    }
    @Synchronized fun snapshot(): Snapshot {
        val retired = pinned.filterNot { it.current }
        val unique = current.values + retired
        return Snapshot(current.values.sumOf { it.size.toLong() }, pinnedBytes, readers,
            retired.sumOf { it.size.toLong() },
            unique.filter { it.heap != null && it.identity === it.heap }.sumOf { it.size.toLong() },
            unique.filter { (it.heap != null && it.identity !== it.heap) || it.native?.nativeBacked == false }.sumOf { it.size.toLong() },
            unique.filter { it.native?.nativeBacked == true }.sumOf { it.size.toLong() })
    }

    private fun recordMemory() {
        val unique = current.values + pinned.filterNot { it.current }
        val exclusive = unique.filter { it.heap != null && it.identity === it.heap }
        app.apexview.MediaMemoryAccounting.process.record(this,
            app.apexview.MediaMemoryAccounting.Kind.RELAY_EXCLUSIVE,
            exclusive.sumOf { it.size.toLong() }, exclusive.filter { it.readers > 0 }.sumOf { it.size.toLong() })
    }
    @Synchronized fun publish(
        next: Map<String, ByteArray>,
        shared: Map<String, SharedRelayResource> = emptyMap(),
        native: Map<String, app.apexview.tiled.NativeFragmentLease> = emptyMap(),
        sharedHeap: Map<String, app.apexview.tiled.HeapFragmentLease> = emptyMap(),
    ) {
        check(!closed)
        val retainedMedia: Map<String, app.apexview.tiled.FragmentReadLease> = native + sharedHeap
        require(retainedMedia.size == native.size + sharedHeap.size &&
            next.size.toLong() + shared.size + retainedMedia.size <= maxResources &&
            next.values.sumOf { it.size.toLong() } + shared.values.sumOf { it.size.toLong() } +
                retainedMedia.values.sumOf { it.size.toLong() } <= maxBytes) {
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
        val replacement = LinkedHashMap<String, Entry>(next.size + shared.size + retainedMedia.size)
        val acquired = mutableListOf<SharedRelayResource.Retained>()
        val redundantNative = mutableListOf<app.apexview.tiled.FragmentReadLease>()
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
            retainedMedia.forEach { (name, lease) ->
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
            acquired.forEach { owner ->
                try { owner.release() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
        val retained = replacement.values.toSet()
        val previous = current
        retained.forEach { it.current = true }
        // Commit point: the caller's new leases are now owned by this store.
        // Do not report a failed publication after this point, otherwise a
        // caller's rollback could close a lease that a socket has acquired.
        current = replacement
        previous.values.filterNot { it in retained }.forEach { releaseStep("retired-resource") { retire(it) } }
        redundantNative.forEach { releaseStep("redundant-token") { it.close() } }
        releaseStep("accounting") { recordMemory() }
    }

    sealed interface Acquisition {
        data object Missing : Acquisition
        data object Busy : Acquisition
        class Available(val lease: Lease) : Acquisition
    }

    fun acquire(name: String): Lease? = (tryAcquire(name) as? Acquisition.Available)?.lease

    /** Distinguish a retired/missing segment from temporary reader admission.
     * The HTTP caller must not present capacity pressure as permanent absence. */
    @Synchronized fun tryAcquire(name: String): Acquisition {
        val entry = current[name] ?: return Acquisition.Missing
        if (readers >= maxReaders || (entry.readers == 0 && entry.size.toLong() > maxBytes - pinnedBytes))
            return Acquisition.Busy
        if (entry.readers++ == 0) { pinned.add(entry); pinnedBytes += entry.size }
        readers++
        recordMemory()
        return Acquisition.Available(Lease(entry.heap, {
            synchronized(this) {
                check(entry.readers > 0 && readers > 0)
                readers--
                if (--entry.readers == 0) {
                    pinned.remove(entry)
                    pinnedBytes -= entry.size
                    if (!entry.current) releaseStep("final-reader") { entry.release() }
                }
                recordMemory()
            }
        }, entry.native))
    }

    private fun releaseStep(owner: String, action: () -> Unit) {
        try { action() }
        catch (failure: Exception) {
            android.util.Log.e("PitwallCast", "RELAY_OWNER_RELEASE_FAILED owner=$owner type=${failure.javaClass.simpleName}")
        }
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
        previous.values.forEach { releaseStep("close-resource") { retire(it) } }
        releaseStep("accounting") { recordMemory() }
    }
}
