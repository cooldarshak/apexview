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

    @Test fun mixedQualityDoesNotSilentlyChangeTheMainFeed() {
        assertNull(PerFeedQualityPolicy.commonDecoderQuality(
            listOf(VideoQuality.P1080, VideoQuality.P540, VideoQuality.P720)))
        assertNull(PerFeedQualityPolicy.commonDecoderQuality(
            listOf(VideoQuality.AUTO, VideoQuality.P540)))
        assertEquals(VideoQuality.P1080, PerFeedQualityPolicy.commonDecoderQuality(
            listOf(VideoQuality.P1080, VideoQuality.P1080)))
        assertEquals(VideoQuality.AUTO, PerFeedQualityPolicy.commonDecoderQuality(
            listOf(VideoQuality.AUTO, VideoQuality.AUTO)))
    }

    @Test fun composedAutoIsLayoutAwareOnlyForAllAutoAndKeepsTheLargestTile() {
        val auto = listOf(VideoQuality.AUTO, VideoQuality.AUTO)
        fun pick(h: List<Float>, previous: Int? = null, pressure: Boolean = false) =
            ComposedAutoQualityPolicy.preferredHeight(auto, h, 1080, previous, pressure)
        assertEquals(540, pick(listOf(500f, 200f)))
        assertEquals(720, pick(listOf(600f, 400f)))
        assertEquals(1080, pick(listOf(1300f, 200f)))
        assertNull(pick(listOf(0f, 500f)))
        assertNull(pick(listOf(Float.NaN, 500f)))
        assertNull(pick(listOf(500f)))
        assertNull(ComposedAutoQualityPolicy.preferredHeight(
            listOf(VideoQuality.P1080, VideoQuality.AUTO), listOf(100f, 100f), 1080, null, true))
        assertNull(ComposedAutoQualityPolicy.preferredHeight(
            listOf(VideoQuality.P720, VideoQuality.P720), listOf(100f, 100f), 1080, 540, true))
    }

    @Test fun composedAutoDeadbandAndPressureDoNotRatchetDownOnRetries() {
        val auto = listOf(VideoQuality.AUTO, VideoQuality.AUTO)
        fun pick(height: Float, previous: Int?, pressure: Boolean = false) =
            ComposedAutoQualityPolicy.preferredHeight(auto, listOf(height, 200f), 1080, previous, pressure)
        assertEquals(540, pick(550f, 540))
        assertEquals(720, pick(640f, 540))
        assertEquals(720, pick(500f, 720))
        assertEquals(480, pick(400f, 720))
        assertEquals(540, pick(640f, 720, true))
        assertEquals(540, pick(640f, 540, true))
        assertEquals(540, pick(550f, 540))
        assertEquals(720, pick(640f, 540))
        assertEquals(270, pick(640f, 270, true))
        assertEquals(270, ComposedAutoQualityPolicy.preferredHeight(auto, listOf(500f, 200f), 270, null, false))
    }

    @Test fun bandwidthBudgetUsesFreshRealSamplesAndNeverManualCaps() {
        val auto = listOf(VideoQuality.AUTO, VideoQuality.AUTO)
        assertEquals(7_000_000L, ComposedBandwidthPolicy.budget(auto, 10_000_000L, 1000L, 2000L, 1f))
        assertEquals(3_500_000L, ComposedBandwidthPolicy.budget(auto, 10_000_000L, 1000L, 2000L, 2f))
        assertNull(ComposedBandwidthPolicy.budget(auto, 10_000_000L, 1000L, 31_001L, 1f))
        assertNull(ComposedBandwidthPolicy.budget(auto, 10_000_000L, -1L, 2000L, 1f))
        assertNull(ComposedBandwidthPolicy.budget(auto, 0L, 1000L, 2000L, 1f))
        assertNull(ComposedBandwidthPolicy.budget(auto, 10_000_000L, 1000L, 2000L, Float.NaN))
        assertNull(ComposedBandwidthPolicy.budget(listOf(VideoQuality.AUTO, VideoQuality.P1080),
            10_000_000L, 1000L, 2000L, 1f))
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
