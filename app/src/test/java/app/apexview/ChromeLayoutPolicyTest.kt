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

    @Test fun overlaysShrinkRealPlayerChromeWidthAtNormalAndLargeFonts() {
        assertFalse(ChromeLayoutPolicy.compactWithClearance(
            1080f, 1f, 0f, 0f, 2f))
        assertTrue(ChromeLayoutPolicy.compactWithClearance(
            1080f, 1f, 600f, 600f, 2f))
        assertTrue(ChromeLayoutPolicy.compactWithClearance(
            1200f, 2f, 1f, 0f, 1f))
        assertFalse(ChromeLayoutPolicy.compactWithClearance(
            1200f, 2f, 0f, 0f, 1f))
        assertTrue(ChromeLayoutPolicy.compactWithClearance(
            1080f, 1f, 0f, 0f, Float.NaN))
    }
}
