package app.apexview

import java.util.RandomAccess

/** Archive parser owns only one mutable tail per driver. Seal full blocks
 * into the existing exact Rust snapshot registry while HTTP/JSON parsing is
 * still running. A same-UTC event can replace only the unsealed last row.
 * Immutable publications share sealed blocks, never mutable tail storage. */
internal class RollingDriverSnapshots(
    private val nativeRequested: Boolean,
    private val freeze: (List<DriverLaps.Snapshot>) -> List<DriverLaps.Snapshot> =
        { CompactDriverSnapshots.freeze(it) ?: it },
) : AbstractList<DriverLaps.Snapshot>(), RandomAccess {
    private val blocks = ArrayList<List<DriverLaps.Snapshot>>()
    private var tail = ArrayList<DriverLaps.Snapshot>(BLOCK)
    override val size: Int get() = blocks.size * BLOCK + tail.size

    override fun get(index: Int): DriverLaps.Snapshot {
        if (index !in indices) throw IndexOutOfBoundsException("Snapshot $index")
        val block = index / BLOCK
        return if (block < blocks.size) blocks[block][index % BLOCK] else tail[index % BLOCK]
    }

    fun append(value: DriverLaps.Snapshot) {
        // Keep the final row mutable until the next distinct timestamp.
        if (tail.size == BLOCK) {
            blocks.add(if (nativeRequested) freeze(tail) else tail)
            tail = ArrayList(BLOCK)
        }
        tail.add(value)
    }

    fun replaceLast(value: DriverLaps.Snapshot) {
        check(tail.isNotEmpty())
        tail[tail.lastIndex] = value
    }

    fun snapshot(): List<DriverLaps.Snapshot> {
        val sealed = blocks.toList()
        val remaining = tail.toList()
        return Frozen(sealed, if (nativeRequested) freeze(remaining) else remaining)
    }

    internal class Frozen(private val blocks: List<List<DriverLaps.Snapshot>>,
        private val tail: List<DriverLaps.Snapshot>) : AbstractList<DriverLaps.Snapshot>(), RandomAccess {
        override val size: Int get() = blocks.size * BLOCK + tail.size
        override fun get(index: Int): DriverLaps.Snapshot {
            if (index !in indices) throw IndexOutOfBoundsException("Snapshot $index")
            val block = index / BLOCK
            return if (block < blocks.size) blocks[block][index % BLOCK] else tail[index % BLOCK]
        }
        fun retiredAt(index: Int): Boolean {
            if (index !in indices) return false
            val block = index / BLOCK
            val rows = if (block < blocks.size) blocks[block] else tail
            return (rows as? CompactDriverSnapshots)?.retiredAt(index % BLOCK) ?: rows[index % BLOCK].retired
        }
    }
    companion object { internal const val BLOCK = 2048 }
}
