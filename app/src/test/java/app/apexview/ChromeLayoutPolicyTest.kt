package app.apexview

import org.junit.Assert.*
import org.junit.Test

class ChromeLayoutPolicyTest {
    @Test fun phoneUsesTwoRowsWhileTvPreservesWideTransport() {
        assertTrue(ChromeLayoutPolicy.compact(360f, 1f))
        assertTrue(ChromeLayoutPolicy.compact(800f, 2f))
        assertTrue(ChromeLayoutPolicy.compact(960f, 2f))
        assertTrue(ChromeLayoutPolicy.compact(1080f, 2f))
        assertFalse(ChromeLayoutPolicy.compact(1200f, 2f))
        assertFalse(ChromeLayoutPolicy.compact(1080f, 1f))
        assertFalse(ChromeLayoutPolicy.compact(1920f, 2f))
        assertTrue(ChromeLayoutPolicy.compact(Float.NaN, 1f))
    }
}
