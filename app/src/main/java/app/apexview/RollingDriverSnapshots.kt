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

    /** Only retain intersecting immutable blocks. The mutable tail is
     * copied and packed once, never exposed to the UI or decoded/repacked
     * just because the playhead moved across a sealed block. */
    fun window(first: Int, end: Int): List<DriverLaps.Snapshot> {
        require(first in 0..end && end <= size)
        if (first == end) return emptyList()
        val spans = ArrayList<Span>()
        val tailStart = blocks.size * BLOCK
        var at = first
        while (at < minOf(end, tailStart)) {
            val local = at % BLOCK
            val count = minOf(BLOCK - local, end - at)
            spans.add(Span(blocks[at / BLOCK], local, count))
            at += count
        }
        if (at < end) {
            val rows = tail.subList(at - tailStart, end - tailStart).toList()
            spans.add(Span(if (nativeRequested) freeze(rows) else rows, 0, rows.size))
        }
        return Window(spans)
    }

    internal data class Span(val rows: List<DriverLaps.Snapshot>, val first: Int, val count: Int)

    internal class Window(private val spans: List<Span>) : AbstractList<DriverLaps.Snapshot>(), RandomAccess {
        private val ends = IntArray(spans.size)
        init {
            var total = 0
            spans.forEachIndexed { i, span -> total = Math.addExact(total, span.count); ends[i] = total }
        }
        override val size: Int get() = ends.lastOrNull() ?: 0
        private fun spanAt(index: Int): Int {
            if (index !in indices) throw IndexOutOfBoundsException("Snapshot $index")
            var lo = 0
            var hi = ends.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (ends[mid] <= index) lo = mid + 1 else hi = mid
            }
            return lo
        }
        override fun get(index: Int): DriverLaps.Snapshot {
            val i = spanAt(index)
            val span = spans[i]
            return span.rows[span.first + index - if (i == 0) 0 else ends[i - 1]]
        }
        fun retiredAt(index: Int): Boolean {
            if (index !in indices) return false
            val i = spanAt(index)
            val span = spans[i]
            val at = span.first + index - if (i == 0) 0 else ends[i - 1]
            return (span.rows as? CompactDriverSnapshots)?.retiredAt(at) ?: span.rows[at].retired
        }
        fun window(first: Int, end: Int): List<DriverLaps.Snapshot> {
            require(first in 0..end && end <= size)
            if (first == end) return emptyList()
            val selected = ArrayList<Span>()
            var at = first
            while (at < end) {
                val i = spanAt(at)
                val span = spans[i]
                val count = minOf(ends[i], end) - at
                selected.add(Span(span.rows, span.first + at - if (i == 0) 0 else ends[i-1], count))
                at += count
            }
            return Window(selected)
        }
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
        fun window(first: Int, end: Int): List<DriverLaps.Snapshot> {
            require(first in 0..end && end <= size)
            if (first == end) return emptyList()
            val spans = ArrayList<Span>()
            var at = first
            while (at < end) {
                val block = at / BLOCK
                val rows = if (block < blocks.size) blocks[block] else tail
                val local = at % BLOCK
                val count = minOf(rows.size - local, end - at)
                spans.add(Span(rows, local, count))
                at += count
            }
            return Window(spans)
        }
    }
    companion object { internal const val BLOCK = 2048 }
}
