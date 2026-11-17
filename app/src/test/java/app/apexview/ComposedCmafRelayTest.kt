package app.apexview

import app.apexview.cast.ComposedCmafRelay
import app.apexview.cast.local.ReceiverLicenseRequest
import app.apexview.tiled.ProtectedHevcComposedDashPackageBuilder
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ComposedCmafRelayTest {
    @Test fun onlyTheCapabilityPathServesCmafAndRangeBytesRemainIdentical() {
        val relay = ComposedCmafRelay("127.0.0.1", InetAddress.getByName("127.0.0.1"))
        val uri = URI(relay.url)
        fun get(path: String, range: String? = null): Pair<String, ByteArray> = Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" + (range?.let { "Range: $it\r\n" } ?: "") + "\r\n").toByteArray())
            val result = socket.getInputStream().readBytes()
            val split = result.asList().windowed(4).indexOfFirst { it == listOf(13.toByte(), 10.toByte(), 13.toByte(), 10.toByte()) }
            require(split > 0)
            result.copyOfRange(0, split).toString(Charsets.US_ASCII) to result.copyOfRange(split + 4, result.size)
        }
        try {
            val bytes = ByteArray(100) { (it * 3).toByte() }
            val resources = mapOf("manifest.mpd" to "<MPD/>".toByteArray(), "segment-1.m4s" to bytes.copyOf())
            relay.publish(ProtectedHevcComposedDashPackageBuilder.Package("<MPD/>", resources, 2560, 1472, 10, 1, 1, 0, 1))
            assertTrue(get("/manifest.mpd").first.startsWith("HTTP/1.1 404"))
            val path = uri.path.substringBeforeLast('/') + "/segment-1.m4s"
            assertArrayEquals(bytes, get(path).second)
            val ranged = get(path, "bytes=15-29")
            assertTrue(ranged.first.startsWith("HTTP/1.1 206"))
            assertArrayEquals(bytes.copyOfRange(15, 30), ranged.second)
            assertTrue(get(path, "bytes=900-999").first.startsWith("HTTP/1.1 416"))
            assertTrue(get(uri.path.substringBeforeLast('/') + "/../segment-1.m4s").first.startsWith("HTTP/1.1 404"))
        } finally { relay.close() }
    }
    @Test fun receiverLicensingAcceptsContentIdentifiersAndRejectsCredentialInjection() {
        val request = ReceiverLicenseRequest(1234, listOf(1033, 1034, 1035), 1033)
        assertEquals(request, ReceiverLicenseRequest.decode(request.encode()))
        assertThrows(IllegalArgumentException::class.java) {
            ReceiverLicenseRequest.decode(request.encode().put("licenseUrl", "https://untrusted.invalid/"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReceiverLicenseRequest.decode(request.encode().put("ascendontoken", "not-a-real-token"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReceiverLicenseRequest.decode(JSONObject().put("contentId", 0).put("channels", org.json.JSONArray(listOf(1))).put("audioChannel", 1))
        }
    }
}
