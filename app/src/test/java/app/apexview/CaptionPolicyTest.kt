package app.apexview

import org.junit.Assert.*
import org.junit.Test

class CaptionPolicyTest {
    @Test fun languageChoiceIsNormalizedAndNeverInventsUnadvertisedF1Tracks() {
        assertEquals("auto", CaptionPolicy.normalizeLanguage(null))
        assertEquals("auto", CaptionPolicy.normalizeLanguage("javascript:alert(1)"))
        assertEquals("auto", CaptionPolicy.normalizeLanguage(""))
        assertEquals("pt-br", CaptionPolicy.normalizeLanguage("PT-BR"))
        assertEquals("fr", CaptionPolicy.normalizeLanguage(" FR "))
        assertEquals("en", CaptionPolicy.textLanguage("auto", "en"))
        assertEquals("fr", CaptionPolicy.textLanguage("fr", "en"))
        assertEquals("en", CaptionPolicy.textLanguage("unknown", "en"))
        assertEquals(emptyList<CaptionTrackOption>(), CaptionPolicy.tracks(emptyList()))
        val offered = CaptionPolicy.tracks(listOf(
            "en" to "English CC", "en" to "English subtitles",
            "fr" to null, null to "", "pt-br" to "Português",
        ))
        assertEquals(listOf("en", "fr", "und", "pt-br"), offered.map { it.language })
        assertEquals("English CC", offered[0].label)
        assertEquals("French", offered[1].label)
        assertEquals("Language unspecified", offered[2].label)
        assertEquals("Português", offered[3].label)
    }

    @Test fun onlySelectedAudioOrComposedOwnerSuppliesCaptionsAcrossSourceRoutes() {
        val feeds = listOf("main", "leclerc", "verstappen", "alonso")
        // Standalone clear/Widevine, single source, multiple decoders.
        assertEquals("main", CaptionPolicy.owner(feeds, null, null, null, false))
        assertEquals("verstappen", CaptionPolicy.owner(feeds, "verstappen", null, null, false))
        // Composed clear HEVC: the one Media3 host merges source audio/text.
        assertEquals("main", CaptionPolicy.owner(feeds, "verstappen", "main", null, true))
        // Protected composed HEVC: the authorized audio master still owns text.
        assertEquals("leclerc", CaptionPolicy.owner(feeds, "verstappen", "main", "leclerc", true))
        // No made-up caption source when the protected composition has none.
        assertNull(CaptionPolicy.owner(feeds, "main", null, null, true))
        assertNull(CaptionPolicy.owner(emptyList<String>(), null, null, null, false))
    }
}
