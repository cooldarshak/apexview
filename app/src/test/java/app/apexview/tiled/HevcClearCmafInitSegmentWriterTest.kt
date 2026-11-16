package app.apexview.tiled

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HevcClearCmafInitSegmentWriterTest {
    @Test
    fun buildsInspectableClearHvc1InitSegmentAndFiftySampleMediaFragment() {
        val fixtureDir = tileFixtureDirectory()
        assumeTrue("Kvazaar HEVC tile fixtures are not configured", fixtureDir != null)
        val elementaryStream = File(fixtureDir!!, "reassembled.hevc")
        assumeTrue("Reassembled HEVC reference stream is missing", elementaryStream.isFile)

        val nals = HevcBitstream.splitAnnexB(elementaryStream.readBytes())
        val vpsNal = nals.first { it.type == 32 }.bytes
        val spsNal = nals.first { it.type == 33 }.bytes
        val ppsNal = nals.first { it.type == 34 }.bytes
        val sourceVps = HevcParameterSets.parseVps(vpsNal)
        val sourceSps = HevcParameterSets.parseSps(spsNal)
        val sourcePps = HevcParameterSets.parsePps(ppsNal, sourceSps)
        val output = HevcOutputParameterSetWriter.generate(
            vpsNal, spsNal, ppsNal,
            HevcOutputParameterSetWriter.Request(
                vpsId = sourceVps.vpsId,
                spsId = sourceSps.spsId,
                ppsId = sourcePps.ppsId,
                codedWidth = sourceSps.width,
                codedHeight = sourceSps.height,
                tileColumnWidthsInCtbs = requireNotNull(sourcePps.tileColumnWidthsInCtbs),
                tileRowHeightsInCtbs = requireNotNull(sourcePps.tileRowHeightsInCtbs),
                loopFilterAcrossTilesEnabled = requireNotNull(sourcePps.loopFilterAcrossTiles),
                frameRateNumerator = 25
            )
        )
        val accessUnits = splitPictureAccessUnits(nals)
        assertEquals(50, accessUnits.size)
        assertTrue(accessUnits.all { it.size == 2 })

        val init = HevcClearCmafInitSegmentWriter.write(
            HevcClearCmafInitSegmentWriter.Request(
                trackId = 7,
                mediaTimescale = 90_000,
                parameterSets = output
            )
        )
        val samples = accessUnits.map { unit ->
            HevcClearCmafFragmentWriter.Sample(
                data = lengthPrefixNals(unit),
                durationTicks = 3_600,
                compositionTimeOffsetTicks = 0,
                sampleFlags = if (HevcBitstream.parseNalHeader(unit.first()).type in 16..23) {
                    0x02000000L
                } else {
                    0x01010000L
                }
            )
        }
        val media = HevcClearCmafFragmentWriter.write(
            trackId = 7,
            sequenceNumber = 1,
            baseDecodeTimeTicks = 0,
            samples = samples,
            sourceTrackEncrypted = false,
            sourceFragmentHasEncryptionMetadata = false
        )
        val complete = init + media
        val report = CmafBoxInspector.inspect(complete)

        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertEquals("iso6", report.majorBrand)
        assertTrue(report.compatibleBrands.contains("cmfc"))
        assertTrue(report.compatibleBrands.contains("hvc1"))
        assertEquals(listOf("ftyp", "moov", "moof", "mdat"), report.topLevelBoxes)
        assertTrue(report.fragmented)
        assertTrue(report.initializationSegmentPresent)
        assertEquals(1, report.tracks.size)

        val track = report.tracks.single()
        assertEquals(7L, track.trackId)
        assertEquals("vide", track.handler)
        assertEquals(90_000L, track.timescale)
        assertEquals("hvc1", track.sampleEntry)
        assertEquals(sourceSps.displayWidth, track.width)
        assertEquals(sourceSps.displayHeight, track.height)
        assertFalse(track.encrypted)
        assertEquals(1, track.sampleDescriptionCount)
        val config = requireNotNull(track.hevcConfiguration)
        assertEquals(1, config.configurationVersion)
        assertEquals(4, config.nalLengthSize)
        assertEquals(1, config.vpsCount)
        assertEquals(1, config.spsCount)
        assertEquals(1, config.ppsCount)
        assertTrue(config.parameterSetErrors.toString(), config.parameterSetErrors.isEmpty())
        assertEquals(sourceVps.vpsId, config.vps.single().vpsId)
        assertEquals(output.levelIdc, config.sps.single().profileTierLevel.levelIdc)
        assertEquals(output.tileColumnWidthsInCtbs, config.pps.single().tileColumnWidthsInCtbs)
        assertEquals(output.tileRowHeightsInCtbs, config.pps.single().tileRowHeightsInCtbs)
        assertEquals(1, report.fragmentDefaults.size)
        assertEquals(7L, report.fragmentDefaults.single().trackId)
        assertEquals(1L, report.fragmentDefaults.single().defaultSampleDescriptionIndex)

        val fragment = report.fragments.single()
        assertEquals(7L, fragment.trackId)
        assertEquals(90_000L, fragment.timescale)
        assertEquals(0L, fragment.baseDecodeTimeTicks)
        assertEquals(50, fragment.sampleCount)
        assertEquals(180_000L, fragment.totalDurationTicks)
        assertTrue(fragment.timingComplete)
        assertEquals(List(50) { 3_600L }, fragment.sampleTimings.map { it.durationTicks })
        assertTrue(fragment.sampleTimings.all { it.compositionOffsetTicks == 0L })
        assertFalse(fragment.sampleEncryptionBoxPresent)
        assertFalse(fragment.sampleAuxiliarySizesPresent)
        assertFalse(fragment.sampleAuxiliaryOffsetsPresent)
        assertFalse(fragment.sampleGroupEncryptionPresent)

        val outputDirectory = System.getenv("APEXVIEW_HEVC_OUTPUT_PARAMETER_DIR")
        assumeTrue("CI HEVC evidence directory is not configured", !outputDirectory.isNullOrBlank())
        val target = File(outputDirectory!!)
        assertTrue(target.mkdirs() || target.isDirectory)
        val targetFile = File(target, "hevc_clear_cmaf.mp4")
        targetFile.writeBytes(complete)
        File(target, "hevc_clear_cmaf.json").writeText(
            """{
              "result": "CLEAR_CMAF_INIT_FRAGMENT_PASS",
              "container": "ISO-BMFF fragmented MP4",
              "major_brand": "${report.majorBrand}",
              "compatible_brands": [${report.compatibleBrands.joinToString(",") { "\"$it\"" }}],
              "sample_entry": "${track.sampleEntry}",
              "track_id": ${track.trackId},
              "timescale": ${track.timescale},
              "width": ${track.width},
              "height": ${track.height},
              "sample_count": ${fragment.sampleCount},
              "sample_duration_ticks": 3600,
              "size_bytes": ${targetFile.length()},
              "sha256": "${sha256(complete)}",
              "encrypted": false
            }
            """.trimIndent() + "\n"
        )
    }

    @Test
    fun rejectsInvalidTrackIdentityAndTimescale() {
        val fixtureDir = tileFixtureDirectory()
        assumeTrue("Kvazaar HEVC tile fixtures are not configured", fixtureDir != null)
        val nals = HevcBitstream.splitAnnexB(File(fixtureDir!!, "reassembled.hevc").readBytes())
        val vps = nals.first { it.type == 32 }.bytes
        val sps = nals.first { it.type == 33 }.bytes
        val pps = nals.first { it.type == 34 }.bytes
        val parsedVps = HevcParameterSets.parseVps(vps)
        val parsedSps = HevcParameterSets.parseSps(sps)
        val parsedPps = HevcParameterSets.parsePps(pps, parsedSps)
        val output = HevcOutputParameterSetWriter.generate(
            vps, sps, pps,
            HevcOutputParameterSetWriter.Request(
                parsedVps.vpsId, parsedSps.spsId, parsedPps.ppsId,
                parsedSps.width, parsedSps.height,
                requireNotNull(parsedPps.tileColumnWidthsInCtbs),
                requireNotNull(parsedPps.tileRowHeightsInCtbs),
                requireNotNull(parsedPps.loopFilterAcrossTiles), 25
            )
        )
        assertRejected("Track ID") {
            HevcClearCmafInitSegmentWriter.write(
                HevcClearCmafInitSegmentWriter.Request(0, 90_000, output)
            )
        }
        assertRejected("timescale") {
            HevcClearCmafInitSegmentWriter.write(
                HevcClearCmafInitSegmentWriter.Request(7, 0, output)
            )
        }
    }

    private fun splitPictureAccessUnits(nals: List<HevcBitstream.NalUnit>): List<List<ByteArray>> {
        val pictures = mutableListOf<List<ByteArray>>()
        var current = mutableListOf<ByteArray>()
        nals.filter { it.type <= 31 }.forEach { nal ->
            require(nal.bytes.size >= 3) { "Truncated HEVC VCL NAL" }
            val startsPicture = (nal.bytes[2].toInt() and 0x80) != 0
            if (startsPicture) {
                if (current.isNotEmpty()) pictures += current
                current = mutableListOf()
            } else {
                require(current.isNotEmpty()) { "HEVC picture begins with a continuation slice" }
            }
            current += nal.bytes
        }
        if (current.isNotEmpty()) pictures += current
        return pictures
    }

    private fun lengthPrefixNals(nals: List<ByteArray>): ByteArray {
        val payload = ByteArrayOutputStream()
        nals.forEach { nal ->
            payload.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(nal.size).array())
            payload.write(nal)
        }
        return payload.toByteArray()
    }

    private fun tileFixtureDirectory(): File? =
        System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

    private fun assertRejected(text: String, action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected rejection containing " + text)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(text, true))
        }
    }
}
