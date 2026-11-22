package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class CarouselPrefetchPolicyTest {
    @Test fun emptyAndSingleSlideDoNotPreloadUnrelatedArtwork() {
        assertEquals(emptyList<Int>(), carouselPrefetchIndices(0, 0))
        assertEquals(listOf(0), carouselPrefetchIndices(500, 1))
    }

    @Test fun onlyCurrentAndAdjacentImagesAreRequested() {
        assertEquals(listOf(3, 4, 2), carouselPrefetchIndices(3, 10))
        assertEquals(listOf(0, 1, 9), carouselPrefetchIndices(0, 10))
        assertEquals(listOf(9, 0, 8), carouselPrefetchIndices(9, 10))
        assertEquals(listOf(0, 1), carouselPrefetchIndices(0, 2))
    }

    @Test fun endlessPagerAndSignedBoundariesAreSafe() {
        assertEquals(listOf(2, 3, 1), carouselPrefetchIndices(502, 4))
        assertEquals(3, carouselPrefetchIndices(Int.MAX_VALUE, 7).size)
        assertEquals(3, carouselPrefetchIndices(Int.MIN_VALUE, 7).size)
    }
}
