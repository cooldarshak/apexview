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
