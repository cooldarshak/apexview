package app.apexview.tiled

import java.io.File
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HevcSyntheticTileSplitTest {
    @Test fun kotlinAssemblerReconstructsAndDecodesFiftyComplementaryTilePictures() {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Kvazaar tile fixture directory is not configured", !fixturePath.isNullOrBlank())
        val directory = File(fixturePath!!)
        val source = File(directory, "source_tiled.hevc")
        val leftFile = File(directory, "feed_left.hevc")
        val rightFile = File(directory, "feed_right.hevc")
        val referenceFile = File(directory, "reassembled.hevc")
        assumeTrue(
            "Synthetic complementary tile fixture files are missing",
            source.isFile && leftFile.isFile && rightFile.isFile && referenceFile.isFile
        )

        val sourceNals = HevcBitstream.splitAnnexB(source.readBytes())
        val leftNals = HevcBitstream.splitAnnexB(leftFile.readBytes())
        val rightNals = HevcBitstream.splitAnnexB(rightFile.readBytes())
        val sourceVcl = sourceNals.filter { it.type in 0..31 }
        val leftVcl = leftNals.filter { it.type in 0..31 }
        val rightVcl = rightNals.filter { it.type in 0..31 }
        assertEquals(100, sourceVcl.size)
        assertEquals(50, leftVcl.size)
        assertEquals(50, rightVcl.size)
        assertTrue(leftVcl.all { (it.bytes[2].toInt() and 0x80) != 0 })
        assertTrue(rightVcl.all { (it.bytes[2].toInt() and 0x80) == 0 })

        val spsNal = sourceNals.single { it.type == 33 }
        val ppsNal = sourceNals.single { it.type == 34 }
        val sps = HevcParameterSets.parseSps(spsNal.bytes)
        val pps = HevcParameterSets.parsePps(ppsNal.bytes, sps)
        assertEquals(256, sps.width)
        assertEquals(128, sps.height)
        assertTrue(pps.tilesEnabled)
        assertFalse(pps.entropyCodingSyncEnabled)
        assertEquals(2, pps.numTileColumns)
        assertEquals(1, pps.numTileRows)
        assertTrue(sps.picWidthInCtbs % 2 == 0)
        assertEquals(
            listOf(sps.picWidthInCtbs / 2, sps.picWidthInCtbs / 2),
            pps.tileColumnWidthsInCtbs
        )
        assertEquals(listOf(sps.picHeightInCtbs), pps.tileRowHeightsInCtbs)

        System.err.println(
            "HEVC_TILE_PROFILE: width=${sps.width} height=${sps.height} ctb=${sps.ctbSize} " +
                "ctbGrid=${sps.picWidthInCtbs}x${sps.picHeightInCtbs} vui=${sps.vuiParametersPresent} " +
                "spsExtension=${sps.spsExtensionPresent} tiles=${pps.numTileColumns}x${pps.numTileRows} " +
                "tileWidths=${pps.tileColumnWidthsInCtbs} tileHeights=${pps.tileRowHeightsInCtbs} " +
                "dependentSlices=${pps.dependentSliceSegmentsEnabled} entropySync=${pps.entropyCodingSyncEnabled} " +
                "weightedPred=${pps.weightedPredictionEnabled}/${pps.weightedBipredictionEnabled} " +
                "deblockOverride=${pps.deblockingFilterOverrideEnabled} scaling=${pps.scalingListDataPresent} " +
                "listsModification=${pps.listsModificationPresent} sliceHeaderExtension=${pps.sliceSegmentHeaderExtensionPresent} " +
                "ppsExtension=${pps.extensionPresent}"
        )
        val result = try {
            HevcTileStreamAssembler.assembleTwoTileStreams(
                leftFile.readBytes(), rightFile.readBytes()
            )
        } catch (failure: IllegalArgumentException) {
            throw AssertionError(
                "HEVC_TILE_ASSEMBLY_FAILURE: ${failure.message}",
                failure
            )
        }
        assertEquals(50, result.pictureCount)
        assertEquals(2, result.tileSlicesPerPicture)
        assertTrue(result.sliceSegmentAddresses.all { it.first == 0 && it.second > 0 })
        assertEquals(50, result.sliceSegmentAddresses.size)

        val assembledNals = HevcBitstream.splitAnnexB(result.annexB)
        val assembledVcl = assembledNals.filter { it.type in 0..31 }
        assertEquals(
            sourceVcl.map { it.bytes.toList() },
            assembledVcl.map { it.bytes.toList() }
        )
        assertArrayEquals(referenceFile.readBytes(), result.annexB)

        val sourceFrames = HevcStreamAlignmentAnalyzer.analyze(source.readBytes())
        val assembledFrames = HevcStreamAlignmentAnalyzer.analyze(result.annexB)
        assertEquals(50, sourceFrames.size)
        assertEquals(sourceFrames, assembledFrames)
        assertTrue(sourceFrames.all { it.sliceSegmentAddresses.size == 2 })
        assertTrue(sourceFrames.all { it.sliceSegmentAddresses.distinct().size == 2 })

        // CI installs FFmpeg before unit tests. It decodes the bytes emitted by the Kotlin
        // assembler, then compares every output frame hash to the original encoded picture.
        assumeTrue("FFmpeg is not installed; skipped independent decode check", ffmpegAvailable())
        val assembledFile = File.createTempFile("apexview-kotlin-stitched-", ".hevc", directory)
        try {
            assembledFile.writeBytes(result.annexB)
            assertEquals(frameHashes(source), frameHashes(assembledFile))
        } finally {
            assembledFile.delete()
        }
    }


    @Test fun assemblerRejectsSpsReferencingDifferentVps() {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Kvazaar tile fixture directory is not configured", !fixturePath.isNullOrBlank())
        val directory = File(fixturePath!!)
        val left = File(directory, "feed_left.hevc")
        val right = File(directory, "feed_right.hevc")
        assumeTrue("Synthetic complementary tile files are missing", left.isFile && right.isFile)

        val corrupted = ByteArrayOutputStream()
        HevcBitstream.splitAnnexB(left.readBytes()).forEach { nal ->
            val changed = nal.bytes.copyOf()
            if (nal.type == 32) {
                // vps_video_parameter_set_id is the first four RBSP bits after the NAL header.
                val current = (changed[2].toInt() ushr 4) and 0x0f
                val mismatch = (current + 1) and 0x0f
                changed[2] = ((changed[2].toInt() and 0x0f) or (mismatch shl 4)).toByte()
            }
            appendNal(corrupted, changed)
        }

        var rejected = false
        try {
            HevcTileStreamAssembler.assembleTwoTileStreams(
                corrupted.toByteArray(), right.readBytes()
            )
        } catch (error: IllegalArgumentException) {
            rejected = error.message?.contains("references VPS") == true
        }
        assertTrue("Assembler must reject an SPS/VPS identity mismatch", rejected)
    }

    @Test fun assemblerRejectsReversedTileFeeds() {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Kvazaar tile fixture directory is not configured", !fixturePath.isNullOrBlank())
        val directory = File(fixturePath!!)
        val left = File(directory, "feed_left.hevc")
        val right = File(directory, "feed_right.hevc")
        assumeTrue("Synthetic complementary tile files are missing", left.isFile && right.isFile)

        var rejected = false
        try {
            HevcTileStreamAssembler.assembleTwoTileStreams(right.readBytes(), left.readBytes())
        } catch (error: IllegalArgumentException) {
            rejected = error.message?.contains("first/continuation tile slices") == true
        }
        assertTrue("Assembler must reject the reversed tile order", rejected)
    }

    @Test fun assemblerRejectsTemporalLayerHeaderMismatchBeforeReassembly() {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Kvazaar tile fixture directory is not configured", !fixturePath.isNullOrBlank())
        val directory = File(fixturePath!!)
        val left = File(directory, "feed_left.hevc")
        val right = File(directory, "feed_right.hevc")
        assumeTrue("Synthetic complementary tile files are missing", left.isFile && right.isFile)

        val changedRight = ByteArrayOutputStream()
        HevcBitstream.splitAnnexB(right.readBytes()).forEach { nal ->
            val changed = nal.bytes.copyOf()
            if (nal.type in 0..21) {
                val oldHeader = changed[1].toInt() and 0xff
                changed[1] = ((oldHeader and 0xf8) or 0x02).toByte()
            }
            appendNal(changedRight, changed)
        }

        var rejected = false
        try {
            HevcTileStreamAssembler.assembleTwoTileStreams(left.readBytes(), changedRight.toByteArray())
        } catch (error: IllegalArgumentException) {
            rejected = error.message?.contains("temporal_id_plus1") == true
        }
        assertTrue("Assembler must reject mismatched nuh_temporal_id_plus1", rejected)
    }

    @Test fun assemblerRejectsMissingPictureFromOneTileFeed() {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Kvazaar tile fixture directory is not configured", !fixturePath.isNullOrBlank())
        val directory = File(fixturePath!!)
        val left = File(directory, "feed_left.hevc")
        val right = File(directory, "feed_right.hevc")
        assumeTrue("Synthetic complementary tile files are missing", left.isFile && right.isFile)

        val rightNals = HevcBitstream.splitAnnexB(right.readBytes())
        val truncatedRight = ByteArrayOutputStream()
        rightNals.filter { it.type in 32..34 }.forEach { appendNal(truncatedRight, it.bytes) }
        rightNals.filter { it.type in 0..31 }.dropLast(1).forEach { appendNal(truncatedRight, it.bytes) }

        var rejected = false
        try {
            HevcTileStreamAssembler.assembleTwoTileStreams(
                left.readBytes(), truncatedRight.toByteArray()
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Assembler must reject a tile feed with a missing picture", rejected)
    }

    private fun ffmpegAvailable(): Boolean = try {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun frameHashes(file: File): List<String> {
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error",
            "-f", "hevc", "-i", file.absolutePath, "-map", "0:v:0",
            "-f", "framemd5", "-"
        ).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        val exit = process.waitFor()
        assertEquals("FFmpeg failed to decode ${file.name}: ${lines.joinToString("\n")}", 0, exit)
        val hashes = lines.filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringAfterLast(',').trim() }
        assertEquals("Unexpected decoded frame count in ${file.name}", 50, hashes.size)
        return hashes
    }

    private fun appendNal(output: ByteArrayOutputStream, nal: ByteArray) {
        output.write(byteArrayOf(0, 0, 0, 1))
        output.write(nal)
    }
}
