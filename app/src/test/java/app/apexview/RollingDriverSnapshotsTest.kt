package app.apexview

import org.junit.Assert.*
import org.junit.Test

class RollingDriverSnapshotsTest {
    private fun row(position: Int, retired: Boolean = false) = DriverLaps.Snapshot(
        position, listOf(null, null, null), listOf(null, null, null), null,
        null, false, null, null, retired, null, false)

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
