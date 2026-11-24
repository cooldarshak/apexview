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
