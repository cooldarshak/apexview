package app.apexview

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackOffersTest {
    @Test fun dashPlatformDoesNotTurnAnHlsOfferIntoDash() {
        val offers = PlaybackOffers.read(JSONObject("""{"url":"https://example.test/feed.M3U8?token=SECRET"}"""), "WEB_DASH", "license")
        val selected = PlaybackOffers.preferred(offers, true)!!
        assertEquals(ManifestProtocol.HLS, selected.protocol)
        val diagnostic = PlaybackOffers.diagnostic("WEB_DASH", "player_tm", 123, 1004, "WEB_DASH", offers, selected, false)
        assertTrue(diagnostic.contains("selected=HLS"))
        assertFalse(diagnostic.contains("SECRET"))
        assertFalse(diagnostic.contains("example.test"))
    }

    @Test fun selectsAnExplicitDashAlternativeFromTheSameChannel() {
        val offers = PlaybackOffers.read(JSONObject("""{
            "url":"https://example.test/feed.m3u8",
            "variants":[{"url":"https://example.test/feed.mpd", "streamType":"DASH_WV", "laURL":"dash-license"}]
        }"""), "HLS", "hls-license")
        val dash = PlaybackOffers.preferred(offers, true)!!
        assertEquals("https://example.test/feed.mpd", dash.url)
        assertEquals("DASH_WV", dash.streamType)
        assertEquals("dash-license", dash.licenseUrl)
        assertEquals(ManifestProtocol.HLS, PlaybackOffers.preferred(offers, false)!!.protocol)
    }

    @Test fun missingChannelOfferCannotBorrowMetadataOrAnotherChannelsUrl() {
        assertNull(PlaybackOffers.preferred(PlaybackOffers.read(JSONObject("""{
            "metadata":{"url":"https://example.test/other.mpd"},
            "laURL":"https://example.test/license"
        }"""), "DASH", null), true))
    }

    @Test fun urlHintsIgnoreQueryFragmentsAndMisleadingDirectoryNames() {
        assertEquals(ManifestProtocol.DASH, ManifestProtocol.fromUrl("https://example.test/HLS/feed.MPD?next=x.m3u8#hls"))
        assertEquals(ManifestProtocol.UNKNOWN, ManifestProtocol.fromUrl("https://example.test/play?url=feed.mpd"))
        assertEquals(ManifestProtocol.HLS, ManifestProtocol.fromUrl("https://example.test/a.m3u8#fragment"))
    }

    @Test fun actualBodyDeterminesProtocolIncludingExtensionlessOffers() {
        assertEquals(ManifestProtocol.HLS, ManifestProtocol.fromBody("\uFEFF\n#EXTM3U\n#EXT-X-VERSION:7".toByteArray()))
        assertEquals(ManifestProtocol.DASH, ManifestProtocol.fromBody("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>".toByteArray()))
        assertEquals(ManifestProtocol.UNKNOWN, ManifestProtocol.fromBody("<html>Sign in</html>".toByteArray()))
    }

    @Test fun multiPlayRetainsTheSelectedChannelsProtocolAndLicense() {
        val selected = PlayInfo("https://example.test/channel.mpd", "dash-license", "DASH_WV",
            profileLabel = "channel-1033", requestedPlatform = "WEB_DASH", playerType = "player_tm")
        val session = MultiPlay(mapOf(1033 to selected.url), "global-license", "HLS", mapOf(1033 to selected))
        assertSame(selected, session.stream(selected.url, 1033, 123))
        assertEquals("DASH_WV", session.stream(selected.url, 1033, 123).streamType)
    }
}
