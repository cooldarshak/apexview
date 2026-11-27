package app.apexview

/**
 * Append-only replay timestamp history. A sealed full block transfers to
 * the existing bounded Rust timestamp registry immediately, not at EOF.
 * The mutable tail remains private, and every publication receives an exact
 * immutable range. Unsupported spans or native pressure use the same JVM
 * values. Binary search visits at most log2(total rows) samples.
 */
internal class RollingTimingIndex(private val nativeRequested: Boolean,
    private val pack: (LongArray, Int) -> TimingTimestamps? = { values, count -> TimingTimestamps.packed(values, count) }) {
    private val sealed = ArrayList<TimingTimestamps>()
    private var tail = LongArray(32)
    private var used = 0
    private var frozenTail: TimingTimestamps? = null
    private var frozenTailCount = -1
    val size: Int get() = sealed.size * BLOCK + used

    fun lastOrNull(): Long? = if (size == 0) null else this[size - 1]
    operator fun get(index: Int): Long {
        require(index in 0 until size)
        val block = index / BLOCK
        return if (block < sealed.size) sealed[block][index % BLOCK] else tail[index % BLOCK]
    }
    operator fun plusAssign(time: Long) {
        if (used == BLOCK) {
            val immutable = tail
            sealed.add((if (nativeRequested) {
                frozenTail?.takeIf { frozenTailCount == BLOCK } ?: pack(immutable, BLOCK)
            } else null) ?: TimingTimestamps.jvm(immutable, BLOCK))
            tail = LongArray(32)
            used = 0
            frozenTail = null
            frozenTailCount = -1
        }
        if (used == tail.size) tail = tail.copyOf(minOf(BLOCK, tail.size * 2))
        tail[used++] = time
    }
    fun toLongArray(): LongArray = copyRange(0, size)
    fun window(from: Long, until: Long): IntRange? =
        historyWindowRange(size, from, until) { this[it] }

    fun copyRange(first: Int, end: Int): LongArray {
        require(first in 0..end && end <= size)
        val out = LongArray(end - first)
        var at = first
        while (at < end) {
            val block = at / BLOCK
            val local = at % BLOCK
            val count = minOf(BLOCK - local, end - at)
            val target = at - first
            if (block >= sealed.size) tail.copyInto(out, target, local, local + count)
            else {
                val source = sealed[block]
                val array = source.array
                if (array != null) array.copyInto(out, target, local, local + count)
                else for (i in 0 until count) out[target + i] = source[local + i]
            }
            at += count
        }
        return out
    }
    fun freeze(pool: SpeedTimestampPool, first: Int = 0, end: Int = size): TimingTimestamps {
        require(first in 0..end && end <= size)
        // A complete immutable block is already an exact native/JVM owner.
        // Reuse it rather than allocating and packing an identical timeline.
        if (first % BLOCK == 0 && end - first == BLOCK && first / BLOCK < sealed.size)
            return sealed[first / BLOCK]
        if (nativeRequested && end > first && first / BLOCK < sealed.size) {
            val lastBlock = (end - 1) / BLOCK
            val blocks = lastBlock - first / BLOCK + 1
            if (blocks <= 1024) {
                val owners = ArrayList<app.apexview.tiled.NativeHotPath.CompactTimes>(blocks)
                val starts = IntArray(blocks)
                val lengths = IntArray(blocks)
                var at = first
                while (at < end) {
                    val block = at / BLOCK
                    val local = at % BLOCK
                    val count = minOf(BLOCK - local, end - at)
                    val backing = if (block < sealed.size) sealed[block] else {
                        // Pack the exact published mutable tail once per size.
                        // Never pass a mutable array to a publication owner.
                        if (frozenTailCount != used) {
                            frozenTail = TimingTimestamps.packedBlock(tail, used)
                            frozenTailCount = used
                        }
                        frozenTail
                    }
                    val owner = backing?.native ?: break
                    val slot = owners.size
                    owners.add(owner)
                    starts[slot] = local
                    lengths[slot] = count
                    at += count
                }
                if (at == end) TimingTimestamps.packedWindow(owners.toTypedArray(), starts, lengths, end - first)
                    ?.let { return it }
            }
        }
        val exact = copyRange(first, end)
        return pool.freezeTimeline(exact, exact.size)
    }

    companion object { internal const val BLOCK = 2048 }
}
