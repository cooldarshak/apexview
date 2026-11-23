package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedPickerLayoutPolicyTest {
    @Test fun phoneGetsTwoColumnsAndTvUsesAvailableWidth() {
        assertEquals(1, FeedPickerLayoutPolicy.columns(160f))
        assertEquals(2, FeedPickerLayoutPolicy.columns(360f))
        assertEquals(4, FeedPickerLayoutPolicy.columns(600f))
        assertEquals(8, FeedPickerLayoutPolicy.columns(1920f))
    }

    @Test fun unknownAndVeryNarrowWindowsNeverProduceZeroOrOverflowColumns() {
        assertEquals(1, FeedPickerLayoutPolicy.columns(Float.NaN))
        assertEquals(1, FeedPickerLayoutPolicy.columns(Float.POSITIVE_INFINITY))
        assertEquals(1, FeedPickerLayoutPolicy.columns(0f))
        assertEquals(8, FeedPickerLayoutPolicy.columns(100_000f))
    }

    @Test fun changingWidthPreservesEveryFeedWithoutDuplicatingRows() {
        val feeds = (1..24).toList()
        for (width in listOf(160f, 360f, 600f, 1920f)) {
            val rows = feeds.chunked(FeedPickerLayoutPolicy.columns(width))
            assertEquals(feeds, rows.flatten())
        }
    }
}
