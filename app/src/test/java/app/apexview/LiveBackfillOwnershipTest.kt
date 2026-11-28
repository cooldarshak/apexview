package app.apexview

import org.junit.Assert.*
import org.junit.Test

class LiveBackfillOwnershipTest {
    @Test fun appendKeepsPublishedPrefixAndReusesSpareBacking() {
        val earlier = TrackBuilder()
        for (i in 0 until 1000) earlier.add(i * 100L, i.toFloat(), 0f)
        val live = TrackBuilder()
        live.add(100_000L, 1000f, 0f)
        val first = live.build(earlier)
        live.add(100_100L, 1001f, 0f)
        val second = live.build(earlier)
        live.add(100_200L, 1002f, 0f)
        val third = live.build(earlier)
        assertEquals(1001, first.n)
        assertEquals(1002, second.n)
        assertEquals(1003, third.n)
        assertSame(second.timeline.array, third.timeline.array)
        assertEquals(1000f, first.at(100_000L)!!.first, 0f)
        assertNull(first.at(100_100L))
        assertEquals(1002f, third.at(100_200L)!!.first, 0f)
    }

    @Test fun backfillReplacementDoesNotMutateEarlierPublication() {
        val first = TrackBuilder().apply { add(100, 1f, 2f) }
        val second = TrackBuilder().apply { add(100, 9f, 8f) }
        val live = TrackBuilder().apply { add(200, 3f, 4f) }
        val old = live.build(first)
        val next = live.build(second)
        assertEquals(1f, old.at(100)!!.first, 0f)
        assertEquals(9f, next.at(100)!!.first, 0f)
        assertEquals(3f, next.at(200)!!.first, 0f)
        assertNotSame(old.timeline.array, next.timeline.array)
    }
}
