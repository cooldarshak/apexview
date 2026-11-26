package app.apexview.cast.local

import app.apexview.VideoQuality
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DirectQualityProtocolTest {
    @Test fun qualityCommandsAreLoadBoundAndStrictlyValidated() {
        val command = DirectQualityProtocol.command("load-2", VideoQuality.P540)
        assertEquals(VideoQuality.P540, DirectQualityProtocol.decode(command, "load-2"))
        assertNull(DirectQualityProtocol.decode(command, "load-1"))
        assertNull(DirectQualityProtocol.decode(command, ""))
        assertThrows(IllegalArgumentException::class.java) {
            DirectQualityProtocol.decode(command.put("height", 555), "load-2")
        }
        assertThrows(IllegalArgumentException::class.java) { DirectQualityProtocol.parseHeight("540") }
    }
    @Test fun actualReceiverOfferMetadataRoundTripsAndManualSelectionIsBounded() {
        val offers = listOf(640 to 360, 960 to 540, 1920 to 1080)
        assertEquals(offers, DirectQualityProtocol.decodeOffers(DirectQualityProtocol.encodeOffers(offers)))
        assertEquals(540, DirectQualityProtocol.manualHeight(VideoQuality.P540, offers))
        assertEquals(360, DirectQualityProtocol.manualHeight(VideoQuality.P480, offers))
        assertNull(DirectQualityProtocol.manualHeight(VideoQuality.P270, offers))
        assertNull(DirectQualityProtocol.manualHeight(VideoQuality.AUTO, offers))
        assertThrows(IllegalArgumentException::class.java) {
            DirectQualityProtocol.decodeOffers(JSONArray().put(JSONArray().put(640).put(-1)))
        }
    }
}
