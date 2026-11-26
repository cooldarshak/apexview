package app.apexview

import org.junit.Assert.*
import org.junit.Test

class ComposedSourceRenditionsTest {
    @Test fun reportsPerFeedMixedRenditionsRatherThanAtlasPictureHeight() {
        val actual = ComposedSourceRenditions.fromSourceRects(
            listOf("MAIN", "NOR", "PIA"), 1920 to 1080,
            listOf(1920 to 1080, 960 to 540, 854 to 480))
        assertEquals(1080, actual.getValue("MAIN").second)
        assertEquals(540, actual.getValue("NOR").second)
        assertEquals(480, actual.getValue("PIA").second)
        assertEquals("Current source 540p is below the selected 1080p ceiling.",
            QualityDisclosurePolicy.notice(VideoQuality.P1080, 540))
    }

    @Test fun commonGeometryAndAutoDoNotCreateFalseWarning() {
        val actual = ComposedSourceRenditions.fromSourceRects(listOf("A", "B"),
            1280 to 720, emptyList())
        assertEquals(720, actual.getValue("B").second)
        assertNull(QualityDisclosurePolicy.notice(VideoQuality.AUTO, 480))
        assertNull(QualityDisclosurePolicy.notice(VideoQuality.P720, 720))
        assertTrue(QualityDisclosurePolicy.notice(VideoQuality.P480, 540)?.contains("exceeds") == true)
    }

    @Test fun refusesAmbiguousGeometryWithMissingOrDuplicateSources() {
        assertThrows(IllegalArgumentException::class.java) {
            ComposedSourceRenditions.fromSourceRects(listOf("A", "B"),
                1920 to 1080, listOf(960 to 540))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComposedSourceRenditions.fromSourceRects(listOf("A", "A"),
                1920 to 1080, emptyList())
        }
    }
}


class ManualQualityOfferDisclosureTest {
    @Test fun showsOnlyExactAdvertisedRungsForEveryFeedAndCommonDefault() {
        val first = listOf(640 to 360, 960 to 540, 1920 to 1080, 1920 to 1080)
        val second = listOf(854 to 480, 960 to 540, 1280 to 720)
        assertEquals(listOf(VideoQuality.AUTO, VideoQuality.P360, VideoQuality.P540, VideoQuality.P1080),
            QualityDisclosurePolicy.availableChoices(first))
        assertEquals(listOf(VideoQuality.AUTO, VideoQuality.P480, VideoQuality.P540, VideoQuality.P720),
            QualityDisclosurePolicy.availableChoices(second))
        assertEquals(listOf(VideoQuality.AUTO, VideoQuality.P540),
            QualityDisclosurePolicy.commonChoices(listOf(first, second)))
        assertEquals(listOf(VideoQuality.AUTO), QualityDisclosurePolicy.commonChoices(listOf(first, emptyList())))
        assertEquals(listOf(VideoQuality.AUTO), QualityDisclosurePolicy.availableChoices(listOf(0 to 480, 854 to 0)))
        assertEquals(listOf(VideoQuality.AUTO), QualityDisclosurePolicy.commonChoices(emptyList()))
    }

    @org.junit.Test fun aMissingManualRungIsNeverSilentlyRelabelledAsAvailable() {
        val offered = listOf(1920 to 1080, 1280 to 720)
        val message = QualityDisclosurePolicy.unavailableManualCap(VideoQuality.P540, offered)
        org.junit.Assert.assertNotNull(message)
        org.junit.Assert.assertTrue(message!!.contains("no video at or below 540p"))
        org.junit.Assert.assertNull(QualityDisclosurePolicy.unavailableManualCap(VideoQuality.AUTO, offered))
        org.junit.Assert.assertNull(QualityDisclosurePolicy.unavailableManualCap(
            VideoQuality.P720, offered))
        org.junit.Assert.assertNull(QualityDisclosurePolicy.unavailableManualCap(
            VideoQuality.P540, emptyList()))
        val lower = QualityDisclosurePolicy.unavailableManualCap(VideoQuality.P1080, listOf(960 to 540, 1280 to 720))
        org.junit.Assert.assertTrue(lower?.contains("does not advertise 1080p") == true)
        org.junit.Assert.assertTrue(lower?.contains("Available: 540p, 720p") == true)
        org.junit.Assert.assertNull(QualityDisclosurePolicy.unavailableManualCap(VideoQuality.P540, listOf(0 to 0)))
    }
}

class VideoQualityAdvisoryTest {
    @org.junit.Test fun reportsOnlyRealCodedSizeAndEffectivePlaybackRate() {
        val evidence = VideoQualityAdvisory.Atlas(1920, 2176, 50.0, 1.5f)
        val notice = VideoQualityAdvisory.atlasNotice(evidence)!!
        org.junit.Assert.assertTrue(notice.contains("1920 × 2176"))
        org.junit.Assert.assertTrue(notice.contains("75.0 effective fps"))
        org.junit.Assert.assertTrue(notice.contains("not screen resolution"))
        org.junit.Assert.assertNull(VideoQualityAdvisory.atlasNotice(null))
    }

    @org.junit.Test fun thermalAdviceDoesNotPretendToChangeProtectedOrFixedSourceQuality() {
        val hot = android.os.PowerManager.THERMAL_STATUS_SEVERE
        val single = VideoQualityAdvisory.thermalNotice(hot, DecoderMode.SINGLE, VideoQuality.P1080)!!
        org.junit.Assert.assertTrue(single.contains("unchanged"))
        val fixed = VideoQualityAdvisory.thermalNotice(hot, DecoderMode.MULTIPLE, VideoQuality.P1080)!!
        org.junit.Assert.assertTrue(fixed.contains("fixed quality ceiling"))
        val auto = VideoQualityAdvisory.thermalNotice(hot, DecoderMode.MULTIPLE, VideoQuality.AUTO)!!
        org.junit.Assert.assertTrue(auto.contains("Auto quality"))
        org.junit.Assert.assertNull(VideoQualityAdvisory.thermalNotice(
            android.os.PowerManager.THERMAL_STATUS_LIGHT, DecoderMode.SINGLE, VideoQuality.AUTO))
    }

    @org.junit.Test fun requested480IsNotReportedAsDecoded480WhenSourceIs360() {
        val lower = QualityDisclosurePolicy.notice(VideoQuality.P480, 360)
        org.junit.Assert.assertTrue(lower?.contains("360p") == true)
        org.junit.Assert.assertTrue(lower?.contains("480p") == true)
        val above = QualityDisclosurePolicy.notice(VideoQuality.P480, 540)
        org.junit.Assert.assertTrue(above?.contains("exceeds") == true)
        org.junit.Assert.assertNull(QualityDisclosurePolicy.notice(VideoQuality.AUTO, 360))
    }
}

class QualityFeedNameTest {
    @Test fun metadataNamesDoNotExposeInternalChannelIds() {
        assertEquals("Main · F1 Live", qualityFeedName(Channel("F1 Live", 1025, true), true))
        assertEquals("Verstappen · Onboard", qualityFeedName(Channel("1 Verstappen", 1009,
            racingNumber = 1, tla = "VER", driverName = "Verstappen"), false))
        assertEquals("Main · Russell · Onboard", qualityFeedName(Channel("63 Russell", 1011,
            racingNumber = 63, tla = "RUS", driverName = "Russell"), true))
        assertEquals("Video feed", qualityFeedName(Channel("Channel 1016", 1016), false))
        assertEquals("Driver 6 · Onboard", qualityFeedName(Channel("channel-1039", 1039,
            racingNumber = 6), false))
        val channel = Channel("1 Verstappen", 1009, racingNumber = 1, tla = "VER", driverName = "Verstappen")
        assertEquals("Max Verstappen · Onboard", qualityFeedName(channel, false,
            DriverInfo("1", "VER", 0, "Max", "Verstappen")))
        assertEquals("Verstappen · Onboard", qualityFeedName(channel, false,
            DriverInfo("63", "RUS", 0, "George", "Russell")))
        assertEquals("Verstappen · Onboard", qualityFeedName(channel, false,
            DriverInfo("1", "RUS", 0, "George", "Russell")))
    }
}

class CastCompositionQualityReuseTest {
    private val offers = mapOf(
        "MAIN" to listOf(640 to 360, 1280 to 720, 1920 to 1080),
        "ONBOARD" to listOf(640 to 360, 960 to 540, 1280 to 720))
    private val actual = mapOf("MAIN" to (1920 to 1080), "ONBOARD" to (960 to 540))

    @Test fun exactMixedRungsCanBeReusedButPreviouslyLowerOrHigherRungsCannot() {
        val request = mapOf("MAIN" to 1080, "ONBOARD" to 540)
        assertTrue(QualityDisclosurePolicy.canReuseCastComposition(request, actual, offers, true))
        assertFalse(QualityDisclosurePolicy.canReuseCastComposition(request,
            actual + ("MAIN" to (1280 to 720)), offers, true))
        assertFalse(QualityDisclosurePolicy.canReuseCastComposition(request,
            actual + ("ONBOARD" to (1280 to 720)), offers, true))
        assertFalse(QualityDisclosurePolicy.canReuseCastComposition(request,
            actual + ("ONBOARD" to (640 to 360)), offers, true))
    }

    @Test fun unavailableManualRungRequiresBestKnownLowerOffer() {
        val request = mapOf("MAIN" to 1080, "ONBOARD" to 480)
        assertTrue(QualityDisclosurePolicy.canReuseCastComposition(request,
            actual + ("ONBOARD" to (640 to 360)), offers, true))
        assertFalse(QualityDisclosurePolicy.canReuseCastComposition(request,
            actual + ("ONBOARD" to (640 to 360)), emptyMap(), true))
        assertFalse(QualityDisclosurePolicy.canReuseCastComposition(request, actual, offers, true))
    }

    @Test fun autoReplacementReevaluatesSourceAndRejectsMissingOrInvalidGeometry() {
        val request = mapOf("MAIN" to 0, "ONBOARD" to 540)
        assertTrue(QualityDisclosurePolicy.canReuseCastComposition(request, actual, offers, false))
        assertFalse(QualityDisclosurePolicy.canReuseCastComposition(request, actual, offers, true))
        assertFalse(QualityDisclosurePolicy.canReuseCastComposition(request,
            actual - "ONBOARD", offers, false))
        assertFalse(QualityDisclosurePolicy.canReuseCastComposition(request,
            actual + ("ONBOARD" to (0 to 540)), offers, false))
    }
}
