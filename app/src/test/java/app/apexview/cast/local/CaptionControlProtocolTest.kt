package app.apexview.cast.local

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CaptionControlProtocolTest {
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
}
