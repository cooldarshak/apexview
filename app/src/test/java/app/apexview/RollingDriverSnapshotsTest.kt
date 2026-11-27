package app.apexview

import org.junit.Assert.*
import org.junit.Test

class RollingDriverSnapshotsTest {
    private fun row(position: Int, retired: Boolean = false) = DriverLaps.Snapshot(
        position, listOf(null, null, null), listOf(null, null, null), null,
        null, false, null, null, retired, null, false)

    @Test fun previewSharesOnlyIntersectingBlocksAndCopiesMutableTail() {
        val history = RollingDriverSnapshots(true) { it.toList() }
        val block = RollingDriverSnapshots.BLOCK
        repeat(3 * block + 5) { history.append(row(it, it == 2 * block)) }
        val first = history.window(2 * block - 2, 3 * block + 5) as RollingDriverSnapshots.Window
        history.replaceLast(row(-1))
        assertEquals(block + 7, first.size)
        assertTrue(first.retiredAt(2))
        assertEquals(3 * block + 4, first.last().position)
        val second = first.window(1, 4) as RollingDriverSnapshots.Window
        assertEquals(listOf(2*block-1,2*block,2*block+1), second.map { it.position })
        assertTrue(second.retiredAt(1))
        val full = history.snapshot() as RollingDriverSnapshots.Frozen
        assertEquals(listOf(1,2,3),full.window(1,4).map { it.position })
        assertEquals(-1, full.last().position)
    }

    @Test fun completedBlocksAreFrozenOnceAndSameTimestampCanReplaceTail() {
        var freezes = 0
        val history = RollingDriverSnapshots(true) { freezes++; it.toList() }
        repeat(RollingDriverSnapshots.BLOCK) { history.append(row(it)) }
        history.replaceLast(row(42, true))
        assertEquals(0, freezes)
        history.append(row(99))
        assertEquals(1, freezes)
        val published = history.snapshot()
        history.replaceLast(row(100))
        history.append(row(101))
        assertEquals(42, published[RollingDriverSnapshots.BLOCK - 1].position)
        assertTrue(published[RollingDriverSnapshots.BLOCK - 1].retired)
        assertEquals(99, published.last().position)
        assertEquals(101, history.last().position)
        assertEquals(RollingDriverSnapshots.BLOCK + 1, published.size)
    }
}
