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


    @Test fun kotlinAssemblerReconstructsFourComplementaryTilesInA2x2Grid() {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Kvazaar tile fixture directory is not configured", !fixturePath.isNullOrBlank())
        val directory = File(fixturePath!!)
        val source = File(directory, "source_tiled_2x2.hevc")
        val reference = File(directory, "reassembled_2x2.hevc")
        val feeds = (0..3).map { File(directory, "feed_tile_$it.hevc") }
        assumeTrue(
            "Synthetic 2x2 tile fixtures are missing",
            source.isFile && reference.isFile && feeds.all { it.isFile }
        )

        val sourceNals = HevcBitstream.splitAnnexB(source.readBytes())
        val sourceVcl = sourceNals.filter { it.type in 0..31 }
        assertEquals(200, sourceVcl.size)
        val feedVcl = feeds.map { file ->
            HevcBitstream.splitAnnexB(file.readBytes()).filter { it.type in 0..31 }
        }
        feedVcl.forEach { assertEquals(50, it.size) }
        assertTrue(feedVcl.first().all { (it.bytes[2].toInt() and 0x80) != 0 })
        feedVcl.drop(1).flatten().forEach { assertTrue((it.bytes[2].toInt() and 0x80) == 0) }

        val sps = HevcParameterSets.parseSps(sourceNals.single { it.type == 33 }.bytes)
        val pps = HevcParameterSets.parsePps(sourceNals.single { it.type == 34 }.bytes, sps)
        assertTrue(pps.tilesEnabled)
        assertEquals(2, pps.numTileColumns)
        assertEquals(2, pps.numTileRows)
        assertEquals(2, pps.tileColumnWidthsInCtbs?.size)
        assertEquals(2, pps.tileRowHeightsInCtbs?.size)
        assertEquals(sps.picWidthInCtbs, pps.tileColumnWidthsInCtbs?.sum())
        assertEquals(sps.picHeightInCtbs, pps.tileRowHeightsInCtbs?.sum())

        val result = try {
            HevcTileStreamAssembler.assembleFourTileStreams(feeds.map { it.readBytes() })
        } catch (failure: IllegalArgumentException) {
            throw AssertionError("HEVC_TILE_2X2_ASSEMBLY_FAILURE: ${failure.message}", failure)
        }
        assertEquals(50, result.pictureCount)
        assertEquals(4, result.tileSlicesPerPicture)
        assertEquals(50, result.tileSliceSegmentAddresses.size)
        val expectedAddresses = result.tileSliceSegmentAddresses.first()
        assertEquals(4, expectedAddresses.size)
        assertEquals(0, expectedAddresses[0])
        assertTrue(expectedAddresses.zipWithNext().all { it.first < it.second })

        val assembledNals = HevcBitstream.splitAnnexB(result.annexB)
        val assembledVcl = assembledNals.filter { it.type in 0..31 }
        assertEquals(sourceVcl.map { it.bytes.toList() }, assembledVcl.map { it.bytes.toList() })
        assertArrayEquals(reference.readBytes(), result.annexB)

        val sourceFrames = HevcStreamAlignmentAnalyzer.analyze(source.readBytes())
        val assembledFrames = HevcStreamAlignmentAnalyzer.analyze(result.annexB)
        assertEquals(sourceFrames, assembledFrames)
        assertEquals(50, sourceFrames.size)
        assertTrue(sourceFrames.all { it.sliceSegmentAddresses.size == 4 })
        assertTrue(sourceFrames.all { it.sliceSegmentAddresses.distinct().size == 4 })

        assumeTrue("FFmpeg is not installed; skipped independent decode check", ffmpegAvailable())
        val assembledFile = File.createTempFile("apexview-kotlin-stitched-2x2-", ".hevc", directory)
        try {
            assembledFile.writeBytes(result.annexB)
            assertEquals(frameHashes(source), frameHashes(assembledFile))
        } finally {
            assembledFile.delete()
        }
    }

    @Test fun tizenGridComposerRewritesFourSourceTilesIntoOneTwoByTwoPicture() {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Kvazaar 2x2 tile fixture directory is not configured", !fixturePath.isNullOrBlank())
        val directory = File(fixturePath!!)
        val source = File(directory, "source_tiled_2x2.hevc")
        assumeTrue("Synthetic 2x2 source stream is missing", source.isFile)

        val sourceBytes = source.readBytes()
        val result = try {
            HevcTizenGridComposer.composeAnnexBStreams(
                // Four logical source feeds exercise the Tizen (feed, sourceTile) cell mapping.
                // The pixel oracle remains this same deterministic source frame for all four
                // cells; independently encoded full-frame sources are covered by the 2x1 path.
                feedStreams = List(4) { sourceBytes.copyOf() },
                grid = HevcOutputGridDimensions(columns = 2, rows = 2),
                cells = listOf(
                    HevcTizenGridComposer.CellSelection(0, 0, 0, 0),
                    HevcTizenGridComposer.CellSelection(1, 1, 1, 0),
                    HevcTizenGridComposer.CellSelection(2, 2, 0, 1),
                    HevcTizenGridComposer.CellSelection(3, 3, 1, 1)
                ),
                frameRateNumerator = 25,
                outputNalLengthSize = 4
            )
        } catch (failure: IllegalArgumentException) {
            throw AssertionError("HEVC_TIZEN_GRID_2X2_COMPOSITION_FAILURE: ${failure.message}", failure)
        }

        assertEquals(50, result.pictures.size)
        assertEquals(4, result.sourcePictureIdentities.size)
        assertEquals(200, result.pictures.sumOf { it.evidence.size })
        assertTrue(result.pictures.all { picture ->
            picture.evidence.map { it.feedIndex } == listOf(0, 1, 2, 3) &&
                picture.evidence.map { it.sourceTileIndex } == listOf(0, 1, 2, 3)
        })
        assertEquals(256, result.codedWidth)
        assertEquals(128, result.codedHeight)
        assertEquals(listOf(2, 2), result.tileColumnWidthsInCtbs)
        assertEquals(listOf(1, 1), result.tileRowHeightsInCtbs)
        val outputSps = HevcParameterSets.parseSps(result.outputParameters.sps)
        assertEquals(4, outputSps.picWidthInCtbs)
        assertEquals(2, outputSps.picHeightInCtbs)
        // The generated grid keeps source loop-filter policy unless every selected seam is safe.
        val expectedDestinationAddresses = listOf(0, 2, 4, 6)
        result.pictures.forEachIndexed { frameIndex, picture ->
            assertEquals(
                "Frame $frameIndex destination CTB addresses: " +
                    picture.evidence.map { it.destinationCtbAddress },
                expectedDestinationAddresses,
                picture.evidence.map { it.destinationCtbAddress }
            )
        }

        assumeTrue("FFmpeg is not installed; skipped independent 2x2 decode check", ffmpegAvailable())
        val composedFile = File.createTempFile("apexview-tizen-grid-2x2-", ".hevc", directory)
        try {
            composedFile.writeBytes(result.annexB)
            assertEquals(
                "Rewritten 2x2 output must decode to the original source pixels",
                frameHashes(source),
                frameHashes(composedFile)
            )
        } finally {
            composedFile.delete()
        }
    }

    @Test fun fourTileAssemblerRejectsAFeedInTheWrongTilePosition() {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Kvazaar tile fixture directory is not configured", !fixturePath.isNullOrBlank())
        val directory = File(fixturePath!!)
        val feeds = (0..3).map { File(directory, "feed_tile_$it.hevc") }
        assumeTrue("Synthetic 2x2 tile fixtures are missing", feeds.all { it.isFile })
        var rejected = false
        try {
            HevcTileStreamAssembler.assembleFourTileStreams(
                listOf(feeds[1], feeds[0], feeds[2], feeds[3]).map { it.readBytes() }
            )
        } catch (error: IllegalArgumentException) {
            rejected = error.message?.isNotBlank() == true
        }
        assertTrue("Assembler must reject a tile stream assigned to the wrong grid cell", rejected)
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
            rejected = error.message?.isNotBlank() == true
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

        val leftTemporalIds = HevcBitstream.splitAnnexB(left.readBytes())
            .filter { it.type in 0..21 }
            .map { it.temporalIdPlus1 }
        val changedRight = ByteArrayOutputStream()
        var vclIndex = 0
        HevcBitstream.splitAnnexB(right.readBytes()).forEach { nal ->
            val changed = nal.bytes.copyOf()
            if (nal.type in 0..21) {
                val leftTemporalId = leftTemporalIds[vclIndex++]
                val oppositeTemporalId = if (leftTemporalId == 1) 2 else 1
                val oldHeader = changed[1].toInt() and 0xff
                changed[1] = ((oldHeader and 0xf8) or oppositeTemporalId).toByte()
            }
            appendNal(changedRight, changed)
        }

        var rejected = false
        try {
            HevcTileStreamAssembler.assembleTwoTileStreams(left.readBytes(), changedRight.toByteArray())
        } catch (error: IllegalArgumentException) {
            rejected = error.message?.contains("NAL header mismatch") == true
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
