package app.apexview

import org.junit.Assert.*
import org.junit.Test

class RollingTimingIndexTest {
    @Test fun exactHistorySurvivesBlockSealsAndRepeatedTimestamps() {
        var packs = 0
        val index = RollingTimingIndex(true) { values, count ->
            packs++
            TimingTimestamps.jvm(values, count)
        }
        val n = RollingTimingIndex.BLOCK * 2 + 9
        val expected = LongArray(n) { 1_700_000_000_000L + it / 3 }
        expected.forEach { index += it }
        assertEquals(2, packs)
        assertEquals(n, index.size)
        assertArrayEquals(expected, index.toLongArray())
        val first = RollingTimingIndex.BLOCK - 2
        val end = RollingTimingIndex.BLOCK + 5
        assertArrayEquals(expected.copyOfRange(first, end), index.copyRange(first, end))
        val from = expected[first]
        val until = expected[end]
        assertEquals(expected.indexOfLast { it <= from }..expected.indexOfLast { it <= until },
            index.window(from, until))
        val published = index.copyRange(0, index.size)
        index += expected.last() + 1
        assertArrayEquals(expected, published)
    }

    @Test fun nativeAdmissionFailureKeepsExactReferenceHistory() {
        val index = RollingTimingIndex(true) { _, _ -> null }
        val expected = LongArray(RollingTimingIndex.BLOCK + 2) { it.toLong() * 5 }
        expected.forEach { index += it }
        assertArrayEquals(expected, index.toLongArray())
        assertEquals(null, index.window(-20, -1))
        assertEquals(0..0, index.window(-20, 0))
        assertEquals(expected.last(), index.lastOrNull())
    }

    @Test fun disabledNativeModeDoesNotInvokePack() {
        val index = RollingTimingIndex(false) { _, _ -> error("native mode disabled") }
        assertNull(index.lastOrNull())
        assertNull(index.window(0, 10))
        repeat(RollingTimingIndex.BLOCK + 1) { index += it.toLong() }
        assertEquals(0L, index[0])
        assertEquals(RollingTimingIndex.BLOCK.toLong(), index.lastOrNull())
    }
}
