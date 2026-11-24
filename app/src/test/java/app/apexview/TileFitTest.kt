package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class TileFitTest {
    private val renditions = listOf(640 to 360, 1280 to 720, 1920 to 1080)

    @Test
    fun theSmallestThatFillsTheTile() {
        assertEquals(1280 * 720, pixelCeiling(renditions, 700, 400))
        assertEquals(640 * 360, pixelCeiling(renditions, 640, 360))
    }

    @Test
    fun aTileBiggerThanEverythingGetsTheLargest() {
        assertEquals(1920 * 1080, pixelCeiling(renditions, 2076, 1168))
    }

    @Test
    fun bothSidesMustFill() {
        assertEquals(1920 * 1080, pixelCeiling(renditions, 1000, 800))
    }

    @Test
    fun beforeAnySizeIsKnownNothingIsHeldBack() {
        assertEquals(1920 * 1080, pixelCeiling(renditions, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun manualQualityChoosesTheRequestedRenditionWhenAvailable() {
        assertEquals(720, highestAvailableVideoHeightAtOrBelow(renditions, 720))
        assertEquals(1080, highestAvailableVideoHeightAtOrBelow(renditions, 1080))
    }

    @Test
    fun manualQualityFallsBackToTheHighestAvailableRenditionBelowTheCap() {
        assertEquals(360, highestAvailableVideoHeightAtOrBelow(listOf(640 to 360, 1920 to 1080), 720))
        assertEquals(null, highestAvailableVideoHeightAtOrBelow(listOf(1920 to 1080), 720))
    }

    @Test
    fun aManualQualityBelowTheOnlyOfferedRenditionHasNoValidForcedHeight() {
        assertEquals(null, highestAvailableVideoHeightAtOrBelow(
            listOf(1920 to 1080), 540))
        assertEquals(540, highestAvailableVideoHeightAtOrBelow(
            listOf(960 to 540, 1920 to 1080), 720))
        org.junit.Assert.assertFalse(matchesManualQuality(540, null, 1080))
        org.junit.Assert.assertFalse(matchesManualQuality(720, 540, 1080))
        org.junit.Assert.assertTrue(matchesManualQuality(720, 540, 540))
        org.junit.Assert.assertTrue(matchesManualQuality(0, null, 1080))
    }

    @Test
    fun aFeedAloneHasTheWholeConnection() {
        assertEquals(listOf(1f), bandwidthShares(listOf(1920f * 1080f)))
    }

    @Test
    fun equalTilesShareEqually() {
        assertEquals(listOf(0.25f, 0.25f, 0.25f, 0.25f), bandwidthShares(List(4) { 960f * 540f }))
    }

    @Test
    fun theBigTileGetsTheMostAndSmallOnesKeepAFloor() {
        val shares = bandwidthShares(listOf(1600f * 900f, 100f * 56f, 100f * 56f, 100f * 56f))
        assertEquals(0.988f, shares[0], 0.001f)
        assertEquals(0.1f, shares[1], 0.001f)
    }

    @Test
    fun tilesWithNoSizeYetSplitEvenly() {
        assertEquals(listOf(0.5f, 0.5f), bandwidthShares(listOf(0f, 0f)))
    }
}
