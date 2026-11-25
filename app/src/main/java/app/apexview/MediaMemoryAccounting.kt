package app.apexview

/** Explicit unique heap ownership, distinct from OS PSS and future reservations.
 * Shared fragment and sidecar backing is charged at its original owner, never
 * again at the relay or DASH reader. Weak keys avoid diagnostic ownership. */
internal class MediaMemoryAccounting {
    enum class Kind { ARCHIVE_CACHE, SHARED_FRAGMENT, RELAY_EXCLUSIVE, DASH_EXCLUSIVE,
        SIDECAR, CLEAR_SOURCE, PROTECTED_SOURCE, BAKED_QUEUE }
    data class Usage(val owned: Long, val pinned: Long)
    private class Entry(val kind: Kind, var owned: Long, var pinned: Long)
    private val entries = java.util.WeakHashMap<Any, Entry>()
    @Synchronized fun record(owner: Any, kind: Kind, owned: Long, pinned: Long = 0L) {
        require(owned >= 0 && pinned in 0..owned)
        if (owned == 0L) { entries.remove(owner); return }
        val previous = entries[owner]
        if (previous == null) entries[owner] = Entry(kind, owned, pinned)
        else { check(previous.kind == kind); previous.owned = owned; previous.pinned = pinned }
    }
    @Synchronized fun snapshot(): Map<Kind, Usage> {
        val result = linkedMapOf<Kind, Usage>()
        for (entry in entries.values) {
            val previous = result[entry.kind] ?: Usage(0, 0)
            result[entry.kind] = Usage(Math.addExact(previous.owned, entry.owned),
                Math.addExact(previous.pinned, entry.pinned))
        }
        return result
    }
    companion object { val process = MediaMemoryAccounting() }
}
