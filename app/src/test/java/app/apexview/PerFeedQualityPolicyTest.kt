package app.apexview

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

class PerFeedQualityPolicyTest {
    @Test fun commonQualityKeepsOneDecoderContract() {
        assertEquals(VideoQuality.P540,
            PerFeedQualityPolicy.commonDecoderQuality(listOf(VideoQuality.P540, VideoQuality.P540)))
        assertNull(PerFeedQualityPolicy.commonDecoderQuality(listOf(VideoQuality.P1080, VideoQuality.P540)))
        assertNull(PerFeedQualityPolicy.commonDecoderQuality(listOf(VideoQuality.AUTO, VideoQuality.P540)))
        assertNull(PerFeedQualityPolicy.commonDecoderQuality(emptyList()))
    }

    @Test fun mixedQualityFallsBackOnlyForProtectedComposition() {
        val requested = listOf(VideoQuality.P1080, VideoQuality.P540, VideoQuality.P720)
        assertNull(PerFeedQualityPolicy.commonDecoderQuality(requested))
        assertEquals(VideoQuality.P540, PerFeedQualityPolicy.protectedUniformQuality(requested))
        assertEquals(VideoQuality.P720, PerFeedQualityPolicy.protectedUniformQuality(
            listOf(VideoQuality.P1080, VideoQuality.P720)))
        assertEquals(VideoQuality.P540, PerFeedQualityPolicy.protectedUniformQuality(
            listOf(VideoQuality.AUTO, VideoQuality.P540)))
        assertEquals(VideoQuality.P1080, PerFeedQualityPolicy.protectedUniformQuality(
            listOf(VideoQuality.AUTO, VideoQuality.P1080)))
    }

    @Test fun uniformProtectedQualityRetainsOriginalSelection() {
        assertEquals(VideoQuality.AUTO, PerFeedQualityPolicy.protectedUniformQuality(
            listOf(VideoQuality.AUTO, VideoQuality.AUTO)))
        assertEquals(VideoQuality.P1080, PerFeedQualityPolicy.protectedUniformQuality(
            listOf(VideoQuality.P1080, VideoQuality.P1080)))
        assertEquals(VideoQuality.P270, PerFeedQualityPolicy.protectedUniformQuality(
            listOf(VideoQuality.P270, VideoQuality.P1080)))
        assertNull(PerFeedQualityPolicy.protectedUniformQuality(emptyList()))
        assertNull(PerFeedQualityPolicy.commonDecoderQuality(
            listOf(VideoQuality.P1080, VideoQuality.P540)))
    }

    @Test fun qualityPresetsOfferMoreThanTwoHardcodedResolutions() {
        assertEquals(540, VideoQuality.P540.targetHeight)
        assertEquals(360, VideoQuality.P360.targetHeight)
        assertEquals(480, VideoQuality.P480.targetHeight)
        assertEquals(1080, VideoQuality.P1080.targetHeight)
    }
}

@RunWith(RobolectricTestRunner::class)
class PerFeedQualitySettingsTest {
    @Test fun aFeedOverridesDefaultAndPersistsAcrossSettingsInstances() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("f1mv", 0).edit().clear().commit()
        val settings = Settings(context)
        settings.updateVideoQuality(VideoQuality.P720)
        assertEquals(VideoQuality.P720, settings.videoQualityForFeed("VER"))
        settings.updateFeedVideoQuality("VER", VideoQuality.P1080)
        settings.updateFeedVideoQuality("NOR", VideoQuality.P540)
        assertEquals(VideoQuality.P1080, Settings(context).videoQualityForFeed("VER"))
        assertEquals(VideoQuality.P540, Settings(context).videoQualityForFeed("NOR"))
        assertEquals(VideoQuality.P720, Settings(context).videoQualityForFeed("HAM"))
        settings.updateFeedVideoQuality("VER", VideoQuality.AUTO)
        assertEquals(VideoQuality.AUTO, Settings(context).videoQualityForFeed("VER"))
        settings.updateFeedVideoQuality("VER", null)
        assertNull(Settings(context).feedVideoQualityOverride("VER"))
        assertEquals(VideoQuality.P720, Settings(context).videoQualityForFeed("VER"))
        context.getSharedPreferences("f1mv", 0).edit().clear().commit()
    }
}
