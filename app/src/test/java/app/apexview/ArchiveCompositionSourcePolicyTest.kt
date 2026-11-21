package app.apexview

import org.junit.Assert.*
import org.junit.Test

class ArchiveCompositionSourcePolicyTest {
    private fun stream(name: String, protocol: String, hls: String? = null, group: String = "content-2026") =
        PlayInfo("https://f1.test/${name}.${protocol}", "https://f1.test/license", "DASH_WV",
            profileLabel = name, profileGroup = group, authorizedHlsCandidateUrl = hls)

    @Test fun dashSelectedButAuthorizedHlsAlternativeIsProbedFirst() {
        val sources = listOf(stream("main", "mpd", "https://f1.test/main.m3u8"),
            stream("onboard", "mpd", "https://f1.test/onboard.m3u8"))
        val selected = ArchiveCompositionSourcePolicy.select(sources)
        assertTrue(selected.mayContainClearArchive)
        assertEquals("https://f1.test/main.m3u8", selected.feeds[0].manifestUrl)
        assertEquals("https://f1.test/onboard.m3u8", selected.feeds[1].manifestUrl)
    }

    @Test fun licenseUrlDoesNotForceProtectedWhenSelectedUrlIsHls() {
        val choice = ArchiveCompositionSourcePolicy.select(listOf(
            stream("main", "m3u8"), stream("onboard", "m3u8")))
        assertTrue(choice.mayContainClearArchive)
    }

    @Test fun dashOnlyOffersRemainProtectedAndDoNotInventHls() {
        val choice = ArchiveCompositionSourcePolicy.select(listOf(
            stream("main", "mpd"), stream("onboard", "mpd")))
        assertFalse(choice.mayContainClearArchive)
        assertEquals("https://f1.test/main.mpd", choice.feeds[0].manifestUrl)
    }

    @Test fun partialAlternativesNeverMixHlsAndDashInOnePreflight() {
        val choice = ArchiveCompositionSourcePolicy.select(listOf(
            stream("main", "mpd", "https://f1.test/main.m3u8"), stream("onboard", "mpd")))
        assertFalse(choice.mayContainClearArchive)
        assertTrue(choice.feeds.all { it.manifestUrl.endsWith(".mpd") })
    }

    @Test fun rejectsMixingChannelsFromDifferentSessions() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveCompositionSourcePolicy.select(listOf(
                stream("main", "mpd", group = "session-A"), stream("onboard", "mpd", group = "session-B")))
        }
    }
}
