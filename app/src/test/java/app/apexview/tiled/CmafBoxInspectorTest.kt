package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CmafBoxInspectorTest {
    @Test fun readsBrandsTrackTimescaleAndVideoDimensions() {
        val data = box("ftyp", ascii("iso6") + int(0) + ascii("iso6cmfc"))
            .plus(box("moov", box("trak",
                box("mdia",
                    box("mdhd", byteArrayOf(0,0,0,0) + int(0) + int(0) + int(90000) + int(0) + short(0) + short(0)),
                    box("hdlr", byteArrayOf(0,0,0,0) + int(0) + ascii("vide") + ByteArray(12)),
                    box("minf", box("stbl", box("stsd",
                        byteArrayOf(0,0,0,0) + int(1) +
                            int(86) + ascii("hvc1") + ByteArray(6) + short(1) +
                            ByteArray(16) + short(1920) + short(1080) + ByteArray(50)
                    )))
                )
            )))
        val report = CmafBoxInspector.inspect(data)
        assertEquals("iso6", report.majorBrand)
        assertTrue(report.compatibleBrands.contains("cmfc"))
        assertEquals(1, report.tracks.size)
        val track = report.tracks.single()
        assertEquals("vide", track.handler)
        assertEquals(90000L, track.timescale)
        assertEquals("hvc1", track.sampleEntry)
        assertEquals(1920, track.width)
        assertEquals(1080, track.height)
        assertFalse(track.encrypted)
        assertFalse(report.fragmented)
    }

    @Test fun detectsFragmentedMp4FromMoof() {
        val report = CmafBoxInspector.inspect(box("moof", box("mfhd", ByteArray(8))))
        assertTrue(report.fragmented)
    }


    @Test fun identifiesCbcsProtectionWithoutReadingProtectionPayload() {
        val sinf = box("sinf", box("frma", ascii("hvc1")) +
            box("schm", byteArrayOf(0,0,0,0) + ascii("cbcs") + int(0x00010000)))
        val visualEntry = int(8 + 78 + sinf.size) + ascii("encv") +
            ByteArray(6) + short(1) + ByteArray(16) + short(1280) + short(720) + ByteArray(50) + sinf
        val track = box("trak", box("mdia",
            box("hdlr", byteArrayOf(0,0,0,0) + int(0) + ascii("vide") + ByteArray(12)),
            box("minf", box("stbl", box("stsd", byteArrayOf(0,0,0,0) + int(1) + visualEntry)))
        ))
        val report = CmafBoxInspector.inspect(box("moov", track))
        val metadata = report.tracks.single()
        assertEquals("encv", metadata.sampleEntry)
        assertEquals("hvc1", metadata.originalFormat)
        assertEquals("cbcs", metadata.protectionScheme)
        assertTrue(metadata.encrypted)
    }

    @Test fun rejectsBoxWhoseDeclaredSizeExceedsInput() {
        val malformed = int(100) + ascii("free") + ByteArray(4)
        try {
            CmafBoxInspector.inspect(malformed)
            fail("Expected malformed box to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsInputShorterThanHeader() {
        try {
            CmafBoxInspector.inspect(ByteArray(7))
            fail("Expected short input to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    private fun box(type: String, vararg payloads: ByteArray): ByteArray {
        val payload = payloads.fold(ByteArray(0)) { acc, part -> acc + part }
        return int(payload.size + 8) + ascii(type) + payload
    }
    private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
    private fun short(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()
    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.ISO_8859_1)
}
