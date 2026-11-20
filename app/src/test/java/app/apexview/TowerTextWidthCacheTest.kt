package app.apexview

import org.junit.Assert.*
import org.junit.Test

class TowerTextWidthCacheTest {
    @Test fun repeatedLabelsDoNotRequireRepeatedNativeMeasurement() {
        val cache = TowerTextWidthCache()
        var calls = 0
        fun measure(): Float { calls++; return calls.toFloat() }
        assertEquals(1f, cache.width("LAST", 12f, true, ::measure))
        assertEquals(1f, cache.width("LAST", 12f, true, ::measure))
        assertEquals(1, calls)
        assertEquals(2f, cache.width("LAST", 14f, true, ::measure))
        assertEquals(3f, cache.width("LAST", 12f, false, ::measure))
        assertEquals(4f, cache.width("BEST", 12f, true, ::measure))
        assertEquals(4, calls)
    }

    @Test fun evictsOldWidthsAndNeverRetainsUnboundedStrings() {
        val cache = TowerTextWidthCache(2)
        cache.width("first", 8f, false) { 1f }
        cache.width("second", 8f, false) { 2f }
        assertEquals(1f, cache.width("first", 8f, false) { error("cache miss") })
        cache.width("third", 8f, false) { 3f }
        assertEquals(2, cache.entries())
        assertEquals(9f, cache.width("second", 8f, false) { 9f })
        assertEquals(2, cache.entries())
    }
}
