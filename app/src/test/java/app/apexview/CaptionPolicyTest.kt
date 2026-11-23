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
        // Clear HEVC: selecting a different audio camera remerges its text
        // tracks into the one composed host, not the old source audio tile.
        assertEquals("main", CaptionPolicy.owner(feeds, "verstappen", "main", null, true))
        assertEquals("main", CaptionPolicy.owner(
            feeds, "verstappen", "main", "verstappen", true, clearArchiveComposed = true))
        assertNull(CaptionPolicy.owner(
            feeds, "verstappen", null, "verstappen", true, clearArchiveComposed = true))
        // Protected composed HEVC: the authorized audio master still owns text.
        assertEquals("leclerc", CaptionPolicy.owner(feeds, "verstappen", "main", "leclerc", true))
        assertEquals("leclerc", CaptionPolicy.owner(
            feeds, "verstappen", "main", "leclerc", true, clearArchiveComposed = false))
        // Switching the audible source during protected composition must
        // move the CC owner as well, without replacing the HEVC video host.
        assertEquals("verstappen", CaptionPolicy.owner(
            feeds, "verstappen", "main", "verstappen", true))
        // No made-up caption source when the protected composition has none.
        assertNull(CaptionPolicy.owner(feeds, "main", null, null, true))
        assertNull(CaptionPolicy.owner(emptyList<String>(), null, null, null, false))
    }
}
