package app.apexview

/** Explicit unique heap ownership, distinct from OS PSS and future reservations.
 * Shared fragment and sidecar backing is charged at its original owner, never
 * again at the relay or DASH reader. Weak keys avoid diagnostic ownership. */
internal class MediaMemoryAccounting {
    enum class Kind { ARCHIVE_CACHE, SHARED_FRAGMENT, RELAY_EXCLUSIVE, DASH_EXCLUSIVE,
        SIDECAR, CLEAR_SOURCE, PROTECTED_SOURCE, BAKED_QUEUE }
    data class Usage(val owned: Long, val pinned: Long)
    private class Entry(val id: Long, val kind: Kind, var owned: Long, var pinned: Long)
    private var nextId = 0L
    private val peaks = LongArray(Kind.entries.size)
    private val entries = java.util.WeakHashMap<Any, Entry>()
    @Synchronized fun record(owner: Any, kind: Kind, owned: Long, pinned: Long = 0L) {
        require(owned >= 0 && pinned in 0..owned)
        if (owned == 0L) { entries.remove(owner); return }
        val previous = entries[owner]
        if (previous == null) entries[owner] = Entry(++nextId, kind, owned, pinned)
        else { check(previous.kind == kind); previous.owned = owned; previous.pinned = pinned }
        var total = 0L
        for (entry in entries.values) if (entry.kind == kind) total = Math.addExact(total, entry.owned)
        peaks[kind.ordinal] = maxOf(peaks[kind.ordinal], total)
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
    /** Scalar per-owner identities and simultaneous high-water marks, never object references.
     * Snapshot under one lock so a retired owner cannot appear in the current total. */
    @Synchronized fun diagnosticFields(): String {
        val totals = snapshot()
        return Kind.entries.joinToString("") { kind ->
            val usage = totals[kind] ?: Usage(0, 0)
            " ${kind.name}OwnedBytes=${usage.owned} ${kind.name}PinnedBytes=${usage.pinned}" +
                " ${kind.name}PeakOwnedBytes=${peaks[kind.ordinal]}"
        } + " heapOwnerDetail=" + entries.values.sortedBy { it.id }.joinToString(",") {
            "${it.id}:${it.kind.name}:${it.owned}:${it.pinned}"
        }.ifEmpty { "NONE" } + " heapOwnerPeakScope=PROCESS_LIFETIME"
    }
    companion object { val process = MediaMemoryAccounting() }
}
