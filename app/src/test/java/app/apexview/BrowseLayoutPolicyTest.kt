package app.apexview

import org.junit.Assert.*
import org.junit.Test

class BrowseLayoutPolicyTest {
    @Test fun responsiveCardWidthsNeverOverflowAvailablePhoneOrTvViewports() {
        for (width in listOf(240f, 320f, 360f, 412f, 600f, 960f, 1920f)) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                for (posters in listOf(false, true)) {
                    val card = BrowseLayoutPolicy.cardWidthDp(width, posters, scale)
                    assertTrue("card=$card screen=$width", card <= width - 40f)
                    assertTrue(card > 0f)
                }
            }
        }
    }

    @Test fun portraitPhoneShowsTwoCardsAndNextPeek() {
        for (width in listOf(320f, 360f, 384f, 412f)) {
            val video = BrowseLayoutPolicy.cardWidthDp(width, false, 1f, true)
            val poster = BrowseLayoutPolicy.cardWidthDp(width, true, 1f, true)
            val shelf = width - 40f
            assertTrue("two video cards fit", 2f * video + 12f <= shelf)
            assertTrue("next card peeks", 2f * video + 24f < shelf)
            assertTrue("two posters fit", 2f * poster + 12f <= shelf)
            assertTrue("portrait is not a single oversized card", video <= 165f)
        }
    }

    @Test fun portraitLargeFontDoesNotForceUnreadableTinyTiles() {
        assertEquals(344f, BrowseLayoutPolicy.cardWidthDp(384f, false, 2f, true), 0.01f)
        assertEquals(288f, BrowseLayoutPolicy.heroHeightDp(384f, 824f, 1f, true), 0.01f)
        assertTrue(BrowseLayoutPolicy.heroHeightDp(384f, 824f, 2f, true) >= 400f)
        assertEquals(18f, BrowseLayoutPolicy.sectionGapDp(true, 384f), 0.01f)
    }

    @Test fun landscapeRetainsExistingWidthHeroAndSectionGap() {
        for (width in listOf(360f, 600f, 960f, 1920f))
            for (scale in listOf(1f, 1.5f, 2f))
                for (posters in listOf(false, true))
                    assertEquals(BrowseLayoutPolicy.cardWidthDp(width, posters, scale),
                        BrowseLayoutPolicy.cardWidthDp(width, posters, scale, false), 0f)
        assertEquals(BrowseLayoutPolicy.heroHeightDp(800f, 360f, 1f),
            BrowseLayoutPolicy.heroHeightDp(800f, 360f, 1f, false), 0f)
        assertEquals(28f, BrowseLayoutPolicy.sectionGapDp(false, 384f), 0f)
    }

    @Test fun largerFontScaleEnlargesNavigationTouchRegion() {
        assertEquals(60f, BrowseLayoutPolicy.topBarHeightDp(1f), 0.01f)
        assertEquals(76f, BrowseLayoutPolicy.topBarHeightDp(2f), 0.01f)
        assertEquals(60f, BrowseLayoutPolicy.topBarHeightDp(Float.NaN), 0.01f)
    }

    @Test fun landscapeHeroRemainsInsideAvailableHeight() {
        val landscape = BrowseLayoutPolicy.heroHeightDp(800f, 360f, 1f)
        assertTrue(landscape < 320f)
        assertTrue(landscape <= 360f)
        assertTrue(BrowseLayoutPolicy.heroHeightDp(360f, 800f, 2f) >= 320f)
    }
}
