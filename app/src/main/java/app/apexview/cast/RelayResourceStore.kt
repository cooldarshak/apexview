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
    private class Entry(val bytes: ByteArray) {
        var current = true
        var readers = 0
    }
    private var current = emptyMap<String, Entry>()
    private val pinned = mutableSetOf<Entry>()
    private var readers = 0
    private var pinnedBytes = 0L
    private var closed = false

    class Lease internal constructor(private val bytes: ByteArray, private val release: () -> Unit) : AutoCloseable {
        val size = bytes.size
        private var closed = false
        @Synchronized fun writeTo(output: OutputStream, start: Int, count: Int) {
            check(!closed)
            require(start >= 0 && count >= 0 && start <= size - count)
            output.write(bytes, start, count)
        }
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            release()
        }
    }
    data class Snapshot(val currentBytes: Long, val pinnedBytes: Long, val readers: Int)
    @Synchronized fun snapshot() = Snapshot(current.values.sumOf { it.bytes.size.toLong() }, pinnedBytes, readers)

    @Synchronized fun publish(next: Map<String, ByteArray>) {
        check(!closed)
        require(next.size <= maxResources && next.values.sumOf { it.size.toLong() } <= maxBytes) {
            "Receiver media window exceeds its bounded memory budget"
        }
        val existing = IdentityHashMap<ByteArray, Entry>()
        current.values.forEach { existing[it.bytes] = it }
        val retired = IdentityHashMap<ByteArray, Boolean>()
        pinned.filterNot { it.current }.forEach { retired[it.bytes] = true }
        val seen = IdentityHashMap<ByteArray, Boolean>()
        val replacement = LinkedHashMap<String, Entry>(next.size)
        next.forEach { (name, bytes) ->
            require(name.matches(Regex("[a-zA-Z0-9.-]{1,80}")) && name !in setOf(".", "..") &&
                bytes.isNotEmpty() && seen.put(bytes, true) == null && !retired.containsKey(bytes)) {
                "Invalid or previously retired relay resource"
            }
            replacement[name] = existing[bytes] ?: Entry(bytes)
        }
        val retained = replacement.values.toSet()
        val previous = current
        current = replacement
        previous.values.filterNot { it in retained }.forEach(::retire)
    }

    @Synchronized fun acquire(name: String): Lease? {
        val entry = current[name] ?: return null
        if (readers >= maxReaders || (entry.readers == 0 && entry.bytes.size.toLong() > maxBytes - pinnedBytes))
            return null
        if (entry.readers++ == 0) { pinned.add(entry); pinnedBytes += entry.bytes.size }
        readers++
        return Lease(entry.bytes) {
            synchronized(this) {
                check(entry.readers > 0 && readers > 0)
                readers--
                if (--entry.readers == 0) {
                    pinned.remove(entry)
                    pinnedBytes -= entry.bytes.size
                    if (!entry.current) entry.bytes.fill(0)
                }
            }
        }
    }

    private fun retire(entry: Entry) {
        entry.current = false
        if (entry.readers == 0) entry.bytes.fill(0)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val previous = current
        current = emptyMap()
        previous.values.forEach(::retire)
    }
}
