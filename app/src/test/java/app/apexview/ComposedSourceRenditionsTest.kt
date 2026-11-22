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
        assertNull(QualityDisclosurePolicy.notice(VideoQuality.P480, 540))
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
