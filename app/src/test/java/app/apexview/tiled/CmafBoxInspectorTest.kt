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

    @Test fun readsHevcDecoderConfigurationRecordFieldsWithoutSamples() {
        val hvcC = byteArrayOf(
            1, 0xa1.toByte(),
            0x12, 0x34, 0x56, 0x78,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
            120, 0xf1.toByte(), 0x23, 0xfd.toByte(), 0xfd.toByte(),
            0xfa.toByte(), 0xf9.toByte(), 0x01, 0x2c, 0x9f.toByte(), 0
        )
        val entry = visualSampleEntry("hvc1", 1280, 720, box("hvcC", hvcC))
        val report = inspectVisualEntry(entry)
        val config = report.tracks.single().hevcConfiguration!!
        assertEquals(1, config.configurationVersion)
        assertEquals(2, config.profileSpace)
        assertTrue(config.tierFlag)
        assertEquals(1, config.profileIdc)
        assertEquals(120, config.levelIdc)
        assertEquals(0x123, config.minSpatialSegmentationIdc)
        assertEquals(1, config.parallelismType)
        assertEquals(1, config.chromaFormat)
        assertEquals(10, config.bitDepthLuma)
        assertEquals(9, config.bitDepthChroma)
        assertEquals(300, config.averageFrameRate)
        assertEquals(2, config.constantFrameRate)
        assertEquals(3, config.temporalLayers)
        assertTrue(config.temporalIdNested)
        assertEquals(4, config.nalLengthSize)
        assertEquals(0, config.vpsCount)
        assertEquals(0, config.spsCount)
        assertEquals(0, config.ppsCount)
    }

    @Test fun readsCbcsTrackEncryptionShapeWithoutReturningKidOrIv() {
        val tenc = byteArrayOf(1, 0, 0, 0, 0, 0x19, 1, 16) + ByteArray(16)
        val sinf = box("sinf",
            box("frma", ascii("hvc1")) +
            box("schm", byteArrayOf(0,0,0,0) + ascii("cbcs") + int(0x00010000)) +
            box("schi", box("tenc", tenc))
        )
        val visual = visualSampleEntry("encv", 1280, 720, sinf)
        val metadata = inspectVisualEntry(visual).tracks.single()
        val protection = metadata.trackEncryption!!
        assertEquals("cbcs", metadata.protectionScheme)
        assertTrue(metadata.encrypted)
        assertTrue(protection.defaultIsProtected)
        assertEquals(16, protection.perSampleIvSize)
        assertEquals(1, protection.cryptByteBlock)
        assertEquals(9, protection.skipByteBlock)
        assertNull(protection.constantIvSize)
        assertFalse(metadata.sampleEncryptionBoxPresent)
        assertFalse(metadata.sampleAuxiliarySizesPresent)
        assertFalse(metadata.sampleAuxiliaryOffsetsPresent)
    }

    @Test fun reportsUnsupportedOrMalformedHevcConfigurationWithoutExposingPayload() {
        val invalid = byteArrayOf(2) + ByteArray(22)
        val report = inspectVisualEntry(visualSampleEntry("hvc1", 640, 360, box("hvcC", invalid)))
        assertNull(report.tracks.single().hevcConfiguration)
        assertTrue(report.errors.any { it.contains("HEVC decoder configuration") })
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

    private fun inspectVisualEntry(entry: ByteArray): CmafBoxInspector.Report {
        val data = box("moov", box("trak", box("mdia",
            box("hdlr", byteArrayOf(0,0,0,0) + int(0) + ascii("vide") + ByteArray(12)),
            box("minf", box("stbl", box("stsd", byteArrayOf(0,0,0,0) + int(1) + entry)))
        )))
        return CmafBoxInspector.inspect(data)
    }

    private fun visualSampleEntry(
        type: String,
        width: Int,
        height: Int,
        vararg childBoxes: ByteArray
    ): ByteArray {
        val visualFields = ByteArray(6) + short(1) + ByteArray(16) +
            short(width) + short(height) + ByteArray(50)
        val payload = ascii(type) + visualFields + childBoxes.fold(ByteArray(0)) { acc, value -> acc + value }
        return int(payload.size + 4) + payload
    }

    private fun box(type: String, vararg payloads: ByteArray): ByteArray {
        val payload = payloads.fold(ByteArray(0)) { acc, part -> acc + part }
        return int(payload.size + 8) + ascii(type) + payload
    }
    private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
    private fun short(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()
    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.ISO_8859_1)
}
