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

    @Test fun portraitPhoneBalancesReadableCardsAndNextPreview() {
        for (width in listOf(320f, 360f, 384f, 412f)) {
            val shelf = width - 40f
            val video = BrowseLayoutPolicy.cardWidthDp(width, false, 1f, true)
            val poster = BrowseLayoutPolicy.cardWidthDp(width, true, 1f, true)
            assertEquals("video target at $width", shelf * 0.60f, video, 0.01f)
            assertEquals("poster target at $width", shelf * 0.55f, poster, 0.01f)
            assertTrue("second video card must be visible", video + 12f < shelf)
            assertTrue("never squeeze two complete video cards", 2f * video + 12f > shelf)
            assertTrue("second poster card must be visible", poster + 12f < shelf)
        }
        assertEquals(206.4f, BrowseLayoutPolicy.cardWidthDp(384f, false, 1f, true), 0.01f)
        assertEquals(252f, BrowseLayoutPolicy.cardWidthDp(640f, false, 1f, true), 0.01f)
    }

    @Test fun portraitLargeFontDoesNotForceUnreadableTinyTiles() {
        assertEquals(344f, BrowseLayoutPolicy.cardWidthDp(384f, false, 2f, true), 0.01f)
        assertEquals(308f, BrowseLayoutPolicy.heroHeightDp(384f, 824f, 1f, true), 0.01f)
        assertTrue(BrowseLayoutPolicy.heroHeightDp(384f, 824f, 2f, true) >= 400f)
        assertEquals(23f, BrowseLayoutPolicy.sectionGapDp(true, 384f), 0.01f)
        val normal = BrowseLayoutPolicy.cardWidthDp(384f, false, 1f, true)
        assertTrue(BrowseLayoutPolicy.cardWidthDp(384f, false, 1.5f, true) > normal)
        assertEquals(280f, BrowseLayoutPolicy.cardWidthDp(320f, false, 2f, true), 0.01f)
        assertEquals(240f, BrowseLayoutPolicy.cardWidthDp(280f, false, 1f, true), 0.01f)
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

    @Test fun portraitLargeFontKeepsReadableTwoLineTitlesAndSubtitles() {
        for (width in listOf(320f, 384f, 412f, 600f)) {
            assertEquals(2, BrowseLayoutPolicy.cardTitleMaxLines(true, width))
            assertEquals(1, BrowseLayoutPolicy.cardSubtitleMaxLines(true, width, 1f))
            assertEquals(2, BrowseLayoutPolicy.cardSubtitleMaxLines(true, width, 1.75f))
            assertEquals(2, BrowseLayoutPolicy.cardSubtitleMaxLines(true, width, 2f))
            assertEquals(1, BrowseLayoutPolicy.cardTitleMaxLines(false, width))
            assertEquals(1, BrowseLayoutPolicy.cardSubtitleMaxLines(false, width, 2f))
        }
        assertEquals(1, BrowseLayoutPolicy.cardTitleMaxLines(true, 800f))
        assertEquals(1, BrowseLayoutPolicy.cardSubtitleMaxLines(true, 800f, 2f))
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
