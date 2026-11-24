package app.apexview.cast.local

import org.json.JSONArray
import org.json.JSONObject
import app.apexview.CaptionTrackOption
import org.junit.Assert.*
import org.junit.Test

class CaptionControlProtocolTest {
    @Test fun receiverAdvertisesOnlySupportedCaptionLanguagesAndNoCues() {
        val offered = listOf(CaptionTrackOption("en", "English CC"),
            CaptionTrackOption("fr", "French"), CaptionTrackOption("en", "Duplicate"))
        val encoded = CaptionTrackProtocol.encode(offered)
        assertEquals(2, encoded.length())
        assertEquals(offered.take(2), CaptionTrackProtocol.decode(encoded))
        assertFalse(encoded.toString().contains("cue", ignoreCase = true))
        assertFalse(encoded.toString().contains("license", ignoreCase = true))
        assertFalse(encoded.toString().contains("token", ignoreCase = true))
        assertEquals(emptyList<CaptionTrackOption>(), CaptionTrackProtocol.decode(JSONArray()))
    }

    @Test fun malformedOrOverlongRemoteTrackMetadataFailsClosed() {
        val tooMany = JSONArray().apply {
            repeat(17) { put(JSONArray(listOf("en", "English"))) }
        }
        assertThrows(IllegalArgumentException::class.java) { CaptionTrackProtocol.decode(tooMany) }
        assertThrows(IllegalArgumentException::class.java) {
            CaptionTrackProtocol.decode(JSONArray().put(JSONArray(listOf("en", "x".repeat(65)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptionTrackProtocol.decode(JSONArray().put(JSONArray(listOf("en-us;bad", "English"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptionTrackProtocol.decode(JSONArray().put(JSONArray(listOf("auto", "English"))))
        }
    }

    @Test fun onlySettingsTravelToAuthorizedReceiverWithoutMediaOrCredentials() {
        val packet = CaptionControlProtocol.encode(true, "PT-BR")
        assertEquals("captions", packet.getString("action"))
        assertEquals("pt-br", packet.getString("language"))
        assertEquals(RemoteCaptionPreference(true, "pt-br"), CaptionControlProtocol.decode(packet))
        assertEquals(RemoteCaptionPreference(false, "auto"),
            CaptionControlProtocol.decode(CaptionControlProtocol.encode(false, "bad/url")))
        val wire = packet.toString()
        assertFalse(wire.contains("token", ignoreCase = true))
        assertFalse(wire.contains("license", ignoreCase = true))
        assertFalse(wire.contains("cue", ignoreCase = true))
    }

    @Test fun staleOrMalformedControlsCannotSwitchCaptionTracks() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptionControlProtocol.decode(JSONObject().put("action", "seek")
                .put("enabled", true).put("language", "en"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptionControlProtocol.decode(JSONObject().put("action", "captions")
                .put("enabled", true).put("language", "fr;DROP"))
        }
        assertThrows(org.json.JSONException::class.java) {
            CaptionControlProtocol.decode(JSONObject().put("action", "captions")
                .put("language", "en"))
        }
        assertThrows(org.json.JSONException::class.java) {
            CaptionControlProtocol.decode(JSONObject().put("action", "captions")
                .put("enabled", true))
        }
    }
    @Test fun bakedCaptionControlIsScopedToTheCurrentLoadAndLegacyIsExplicit() {
        val command = CaptionControlProtocol.forLoad(true, "en", "load-a")
        assertTrue(CaptionControlProtocol.matchesLoad(command, "load-a", true))
        assertFalse(CaptionControlProtocol.matchesLoad(command, "load-b", true))
        assertFalse(CaptionControlProtocol.matchesLoad(command, "", false))
        val legacy = CaptionControlProtocol.encode(true, "en")
        assertFalse(CaptionControlProtocol.matchesLoad(legacy, "load-a", true))
        assertTrue(CaptionControlProtocol.matchesLoad(legacy, "load-a", false))
        assertEquals(RemoteCaptionPreference(true, "en"), CaptionControlProtocol.decode(command))
    }
}
