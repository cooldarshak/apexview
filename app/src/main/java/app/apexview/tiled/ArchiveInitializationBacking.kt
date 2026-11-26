package app.apexview.tiled

import app.apexview.MediaMemoryAccounting
import java.io.IOException

/** Exact immutable initialization arrays transferred from capture to one finite
 * session. Borrow only for synchronous parsing/validation/JNI submission.
 * The session may retire while a worker is still reading; wipe only after
 * the last borrow completes. No mutable view is published to Media3 or Cast. */
internal class ArchiveInitializationBacking(owned: List<ByteArray>) : AutoCloseable {
    private var arrays: List<ByteArray>? = owned.toList()
    private val bytes = owned.sumOf { it.size.toLong() }
    private var readers = 0
    private var retired = false

    init {
        require(owned.isNotEmpty() && owned.all { it.isNotEmpty() })
        val identities = java.util.IdentityHashMap<ByteArray, Boolean>()
        require(owned.all { identities.put(it, true) == null })
        record()
    }

    /** The arrays must not be modified, retained or returned from this action.
     * The clear composer borrows them only until its synchronous JNI/heap
     * result is independently validated. */
    fun <T> read(action: (List<ByteArray>) -> T): T {
        val backing = synchronized(this) {
            if (retired) throw IOException("Archive initialization owner retired")
            val value = requireNotNull(arrays)
            readers = Math.addExact(readers, 1)
            record()
            value
        }
        try { return action(backing) }
        finally {
            synchronized(this) {
                check(readers > 0)
                readers--
                retireIfIdle()
                record()
            }
        }
    }

    @Synchronized override fun close() {
        if (retired) return
        retired = true
        retireIfIdle()
        record()
    }

    private fun retireIfIdle() {
        if (retired && readers == 0) {
            arrays?.forEach { it.fill(0) }
            arrays = null
        }
    }

    private fun record() {
        val ownedBytes = if (arrays == null) 0L else bytes
        MediaMemoryAccounting.process.record(this, MediaMemoryAccounting.Kind.CLEAR_SOURCE,
            ownedBytes, if (readers > 0) ownedBytes else 0L)
    }
}
