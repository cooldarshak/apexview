package app.apexview.tiled

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProtectedHevcPictureComposerTest {
    @Test fun composesFourProtectedFeedsOnlyWhenPictureTimingAndCryptoIdentityMatch() {
        val fixture = fixture()
        val iv = ByteArray(16) { (it + 71).toByte() }
        val feeds = listOf(0, 1, 2, 3).mapIndexed { index, destination ->
            feed(
                fixture = fixture,
                feedId = "feed-$index",
                trackId = index + 1L,
                sampleIndex = 0,
                sourceTileIndex = 0,
                destinationAddress = listOf(0, 2, 4, 6)[index],
                iv = iv,
                decodeTime = 0L
            )
        }

        val result = ProtectedHevcPictureComposer.compose(
            feeds = feeds,
            outputSps = fixture.sps,
            outputPps = fixture.pps,
            context = "synthetic four-feed protected picture"
        )

        assertEquals(4, result.contributions.size)
        assertEquals(listOf(0, 2, 4, 6), result.contributions.map { it.destinationAddress })
        assertEquals(listOf(0, 0, 0, 0), result.contributions.map { it.sourceTileIndex })
        assertEquals(0L, result.fullPictureOrderCount)
        assertEquals(4L, result.encryptedByteCount)
        assertEquals(4, result.subsampleRanges.count { it.encryptedBytes == 1L })
        assertTrue(feeds.all {
            it.pocTracker.hasReferenceAnchor && it.pocTracker.hasIrapAnchor &&
                it.pocTracker.irapAnchorPictureOrderCount == 0L
        })

        val layout = ProtectedHevcSampleLayout.inspect(
            result.sampleBytes, result.nalLengthSize, result.subsampleRanges
        )
        assertEquals(4, layout.nals.size)
        assertEquals(4L, layout.encryptedByteCount)
        val accessUnit = ProtectedHevcAccessUnitInspector.inspectLengthPrefixedSample(
            result.sampleBytes, layout, fixture.sps, fixture.pps
        )
        assertEquals(listOf(0, 2, 4, 6), accessUnit.slices.map { it.header.sliceSegmentAddress })
    }

    @Test fun rejectsDifferentPerSampleIvIdentityAcrossFeeds() {
        val fixture = fixture()
        val feeds = listOf(
            feed(fixture, "feed-a", 1L, 0, 0, 0, ByteArray(16) { 1 }, 0L),
            feed(fixture, "feed-b", 2L, 0, 0, 2, ByteArray(16) { 2 }, 0L),
            feed(fixture, "feed-c", 3L, 0, 0, 4, ByteArray(16) { 1 }, 0L),
            feed(fixture, "feed-d", 4L, 0, 0, 6, ByteArray(16) { 1 }, 0L)
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcPictureComposer.compose(feeds, fixture.sps, fixture.pps)
        }
        assertTrue(error.message!!.contains("selected sample IV identity differs"))
    }

    @Test fun rejectsStableButDifferentTimestampEpochInsteadOfRebasingIt() {
        val fixture = fixture()
        val iv = ByteArray(16) { 9 }
        val feeds = listOf(
            feed(fixture, "feed-a", 1L, 0, 0, 0, iv, 0L),
            feed(fixture, "feed-b", 2L, 0, 0, 2, iv, 576_000L),
            feed(fixture, "feed-c", 3L, 0, 0, 4, iv, 0L),
            feed(fixture, "feed-d", 4L, 0, 0, 6, iv, 0L)
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcPictureComposer.compose(feeds, fixture.sps, fixture.pps)
        }
        assertTrue(error.message!!.contains("decode time differs"))
        assertTrue(error.message!!.contains("not silently rebased"))
        assertFalse(feeds.any { it.pocTracker.hasReferenceAnchor })
    }

    @Test fun rejectsSharedMutablePocTrackerAcrossFeeds() {
        val fixture = fixture()
        val iv = ByteArray(16) { 8 }
        val shared = HevcPocTracker()
        val feeds = listOf(0, 1, 2, 3).mapIndexed { index, destination ->
            feed(
                fixture, "feed-$index", index + 1L, 0, 0,
                listOf(0, 2, 4, 6)[index], iv, 0L
            ).copy(pocTracker = shared)
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcPictureComposer.compose(feeds, fixture.sps, fixture.pps)
        }
        assertTrue(error.message!!.contains("each feed requires its own independent POC tracker"))
        assertFalse(shared.hasReferenceAnchor)
    }

    @Test fun rejectsDuplicateOrIncompleteOutputTileCoverage() {
        val fixture = fixture()
        val iv = ByteArray(16) { 3 }
        val feeds = listOf(
            feed(fixture, "feed-a", 1L, 0, 0, 0, iv, 0L),
            feed(fixture, "feed-b", 2L, 0, 0, 2, iv, 0L),
            feed(fixture, "feed-c", 3L, 0, 0, 4, iv, 0L)
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcPictureComposer.compose(feeds, fixture.sps, fixture.pps)
        }
        assertTrue(error.message!!.contains("output PPS tile count must exactly match"))
    }

    @Test fun completePicturesRetainAllSourceTilesAndTheirEncryptedPayloads() {
        val f = fixture()
        val file = File(System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")!!, "source_tiled_2x2.hevc")
        val nals = HevcBitstream.splitAnnexB(file.readBytes())
        val cols = requireNotNull(f.pps.tileColumnWidthsInCtbs)
        val rows = requireNotNull(f.pps.tileRowHeightsInCtbs)
        val output = HevcOutputParameterSetWriter.generate(nals.single { it.type == 32 }.bytes,
            nals.single { it.type == 33 }.bytes, nals.single { it.type == 34 }.bytes,
            HevcOutputParameterSetWriter.Request(f.vps.vpsId, f.sps.spsId, f.pps.ppsId,
                f.sps.picWidthInCtbs * f.sps.ctbSize * 2, f.sps.picHeightInCtbs * f.sps.ctbSize * 2,
                cols + cols, rows + rows, requireNotNull(f.pps.loopFilterAcrossTiles), 25))
        val sps = HevcParameterSets.parseSps(output.sps)
        val pps = HevcParameterSets.parsePps(output.pps, sps)
        val iv = ByteArray(16) { 17 }
        // Three complete feeds in a 2x2 atlas. The final cell repeats feed 3, while each
        // original feed is validated once and owns an independent POC tracker.
        val feeds = (0..2).map { feed(f, "f$it", it + 1L, 0, 0, 0, iv, 0) }
        val result = ProtectedHevcPictureComposer.compose(feeds, sps, pps, fullPictureColumns = 2)
        assertEquals(16, result.contributions.size)
        assertEquals(16L, result.encryptedByteCount)
        feeds.forEach { feed ->
            assertEquals(setOf(0, 1, 2, 3), result.contributions.filter { it.feedId == feed.feedId }.map { it.sourceTileIndex }.toSet())
            assertTrue(feed.pocTracker.hasIrapAnchor)
        }
        val layout = ProtectedHevcSampleLayout.inspect(result.sampleBytes, 4, result.subsampleRanges)
        val au = ProtectedHevcAccessUnitInspector.inspectLengthPrefixedSample(result.sampleBytes, layout, sps, pps)
        assertEquals(16, au.slices.size)
        assertEquals(16, au.slices.map { it.tile.index }.distinct().size)
        var offset = 0
        result.subsampleRanges.forEach { range ->
            offset += range.clearBytes
            repeat(range.encryptedBytes.toInt()) { assertEquals(0xA5.toByte(), result.sampleBytes[offset++]) }
        }
    }

    @Test fun protectedAtlasAcceptsValidatedCellPlacementWithoutChangingCiphertext() {
        val f = fixture()
        val plan = requireNotNull(HevcMixedArchiveAtlasPlan.choose(
            List(2) { HevcMixedArchiveAtlasPlan.Source(f.vps, f.sps, f.pps) }))
        val source = File(System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")!!, "source_tiled_2x2.hevc")
        val nals = HevcBitstream.splitAnnexB(source.readBytes())
        val sourceCols = requireNotNull(f.pps.tileColumnWidthsInCtbs)
        val sourceRows = requireNotNull(f.pps.tileRowHeightsInCtbs)
        val outputCols = (0 until plan.grid.columns).map { column ->
            plan.cells.filter { it.outputColumn == column }
                .map { sourceCols[it.sourceTileIndex % sourceCols.size] }.distinct().single()
        }
        val outputRows = (0 until plan.grid.rows).map { row ->
            plan.cells.filter { it.outputRow == row }
                .map { sourceRows[it.sourceTileIndex / sourceCols.size] }.distinct().single()
        }
        val output = HevcOutputParameterSetWriter.generate(
            nals.single { it.type == 32 }.bytes, nals.single { it.type == 33 }.bytes,
            nals.single { it.type == 34 }.bytes,
            HevcOutputParameterSetWriter.Request(f.vps.vpsId, f.sps.spsId, f.pps.ppsId,
                plan.codedWidth, plan.codedHeight, outputCols, outputRows,
                requireNotNull(f.pps.loopFilterAcrossTiles), 25, uncroppedOutputPicture = true))
        val sps = HevcParameterSets.parseSps(output.sps)
        val pps = HevcParameterSets.parsePps(output.pps, sps)
        val iv = ByteArray(16) { 17 }
        val feeds = (0..1).map { index ->
            feed(f, "mixed-$index", index + 1L, 0, 0, 0, iv, 0)
        }
        val result = ProtectedHevcPictureComposer.compose(
            feeds, sps, pps, mixedCells = plan.cells)
        assertEquals(plan.cells.size, result.contributions.size)
        assertEquals(result.contributions.map { it.destinationAddress }.toSet().size,
            result.contributions.size)
        assertEquals(plan.cells.size.toLong(), result.encryptedByteCount)
        val layout = ProtectedHevcSampleLayout.inspect(
            result.sampleBytes, 4, result.subsampleRanges)
        val inspection = ProtectedHevcAccessUnitInspector.inspectLengthPrefixedSample(
            result.sampleBytes, layout, sps, pps)
        assertEquals(plan.cells.size, inspection.slices.size)
        assertEquals(plan.cells.size, inspection.slices.map { it.tile.index }.distinct().size)
    }

    @Test fun protectedMixedAtlasStillRejectsDifferentEncryptedIvIdentity() {
        val f = fixture()
        val plan = requireNotNull(HevcMixedArchiveAtlasPlan.choose(
            List(2) { HevcMixedArchiveAtlasPlan.Source(f.vps, f.sps, f.pps) }))
        val sourceCols = requireNotNull(f.pps.tileColumnWidthsInCtbs)
        val sourceRows = requireNotNull(f.pps.tileRowHeightsInCtbs)
        val cols = (0 until plan.grid.columns).map { column ->
            plan.cells.filter { it.outputColumn == column }
                .map { sourceCols[it.sourceTileIndex % sourceCols.size] }.distinct().single()
        }
        val rows = (0 until plan.grid.rows).map { row ->
            plan.cells.filter { it.outputRow == row }
                .map { sourceRows[it.sourceTileIndex / sourceCols.size] }.distinct().single()
        }
        val source = File(System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")!!, "source_tiled_2x2.hevc")
        val nals = HevcBitstream.splitAnnexB(source.readBytes())
        val output = HevcOutputParameterSetWriter.generate(
            nals.single { it.type == 32 }.bytes, nals.single { it.type == 33 }.bytes,
            nals.single { it.type == 34 }.bytes,
            HevcOutputParameterSetWriter.Request(f.vps.vpsId, f.sps.spsId, f.pps.ppsId,
                plan.codedWidth, plan.codedHeight, cols, rows,
                requireNotNull(f.pps.loopFilterAcrossTiles), 25, uncroppedOutputPicture = true))
        val sps = HevcParameterSets.parseSps(output.sps)
        val pps = HevcParameterSets.parsePps(output.pps, sps)
        val feeds = listOf(feed(f, "a", 1, 0, 0, 0, ByteArray(16) { 1 }, 0),
            feed(f, "b", 2, 0, 0, 0, ByteArray(16) { 2 }, 0))
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcPictureComposer.compose(feeds, sps, pps, mixedCells = plan.cells)
        }
        assertTrue(error.message!!.contains("selected sample IV identity differs"))
        assertFalse(feeds.any { it.pocTracker.hasReferenceAnchor })
    }

    private data class Fixture(
        val sample: ByteArray,
        val ranges: List<CmafBoxInspector.SubsampleEncryptionRange>,
        val vps: HevcParameterSets.VpsMetadata,
        val sps: HevcParameterSets.SpsMetadata,
        val pps: HevcParameterSets.PpsMetadata
    )

    private fun fixture(): Fixture {
        val path = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Kvazaar tile fixture directory is not configured", !path.isNullOrBlank())
        val file = File(path!!, "source_tiled_2x2.hevc")
        assumeTrue("Synthetic 2x2 HEVC stream is missing", file.isFile)
        val nals = HevcBitstream.splitAnnexB(file.readBytes())
        val vps = HevcParameterSets.parseVps(nals.single { it.type == 32 }.bytes)
        val sps = HevcParameterSets.parseSps(nals.single { it.type == 33 }.bytes)
        val pps = HevcParameterSets.parsePps(nals.single { it.type == 34 }.bytes, sps)
        val firstVcl = nals.indexOfFirst {
            it.type in 16..20 && (it.bytes[2].toInt() and 0x80) != 0
        }
        assumeTrue("Synthetic fixture must begin with an IDR/BLA picture", firstVcl >= 0)
        val nextPicture = (firstVcl + 1 until nals.size).firstOrNull { index ->
            val nal = nals[index]
            nal.type in 0..31 && (nal.bytes[2].toInt() and 0x80) != 0
        } ?: nals.size
        val sliceNals = nals.subList(firstVcl, nextPicture)
            .filter { it.type in 0..31 }
            .map { it.bytes }
        assertEquals(4, sliceNals.size)
        assertTrue(pps.tilesEnabled)
        assertEquals(2, pps.numTileColumns)
        assertEquals(2, pps.numTileRows)

        val sampleBytes = ByteArrayOutputStream()
        val ranges = mutableListOf<CmafBoxInspector.SubsampleEncryptionRange>()
        sliceNals.forEach { nal ->
            sampleBytes.write(byteArrayOf(
                (nal.size ushr 24).toByte(),
                (nal.size ushr 16).toByte(),
                (nal.size ushr 8).toByte(),
                nal.size.toByte()
            ))
            sampleBytes.write(nal, 0, nal.size - 1)
            sampleBytes.write(0xA5)
            ranges += CmafBoxInspector.SubsampleEncryptionRange(4 + nal.size - 1, 1L)
        }
        return Fixture(sampleBytes.toByteArray(), ranges, vps, sps, pps)
    }

    private fun feed(
        fixture: Fixture,
        feedId: String,
        trackId: Long,
        sampleIndex: Int,
        sourceTileIndex: Int,
        destinationAddress: Int,
        iv: ByteArray,
        decodeTime: Long
    ): ProtectedHevcPictureComposer.FeedSample {
        val kid = ByteArray(16) { (it + 11).toByte() }
        val trackEncryption = CmafBoxInspector.TrackEncryptionMetadata(
            version = 1,
            defaultIsProtected = true,
            perSampleIvSize = 16,
            cryptByteBlock = 1,
            skipByteBlock = 9,
            constantIvSize = null,
            defaultKidIdentity = ProtectedValueFingerprint.fromBytes("kid", kid),
            constantIvIdentity = null
        )
        val ptl = fixture.sps.profileTierLevel
        val config = CmafBoxInspector.HevcConfiguration(
            configurationVersion = 1,
            profileSpace = ptl.profileSpace,
            tierFlag = ptl.tierFlag,
            profileIdc = ptl.profileIdc,
            compatibilityFlags = ptl.compatibilityFlags,
            constraintIndicatorFlags = ptl.constraintIndicatorFlags,
            levelIdc = ptl.levelIdc,
            minSpatialSegmentationIdc = 0,
            parallelismType = 0,
            chromaFormat = fixture.sps.chromaFormatIdc,
            bitDepthLuma = fixture.sps.bitDepthLuma,
            bitDepthChroma = fixture.sps.bitDepthChroma,
            averageFrameRate = 0,
            constantFrameRate = 0,
            temporalLayers = fixture.sps.maxSubLayers,
            temporalIdNested = fixture.sps.temporalIdNesting,
            nalLengthSize = 4,
            vpsCount = 1,
            spsCount = 1,
            ppsCount = 1,
            vps = listOf(fixture.vps),
            sps = listOf(fixture.sps),
            pps = listOf(fixture.pps),
            parameterSetErrors = emptyList(),
            parameterSetFingerprints = listOf("vps:synthetic", "sps:synthetic", "pps:synthetic")
        )
        val track = CmafBoxInspector.TrackMetadata(
            trackId = trackId,
            handler = "vide",
            timescale = 50_000L,
            sampleEntry = "encv",
            originalFormat = "hvc1",
            protectionScheme = "cbcs",
            encrypted = true,
            width = fixture.sps.displayWidth,
            height = fixture.sps.displayHeight,
            hevcConfiguration = config,
            trackEncryption = trackEncryption,
            sampleEncryptionBoxPresent = false,
            sampleAuxiliarySizesPresent = false,
            sampleAuxiliaryOffsetsPresent = false,
            sampleGroupDescriptionPresent = false,
            sampleToGroupPresent = false,
            seigSampleGroupDescriptionStructureComplete = true,
            sampleDescriptionCount = 1
        )
        val encryption = CmafBoxInspector.SampleEncryptionMetadata(
            ivSize = 16,
            subsampleRanges = fixture.ranges,
            ivIdentity = ProtectedValueFingerprint.fromBytes("iv", iv)
        )
        val duration = 2_000L
        val timing = CmafBoxInspector.SampleTiming(
            decodeTimeTicks = decodeTime,
            presentationTimeTicks = decodeTime,
            durationTicks = duration,
            compositionOffsetTicks = 0,
            sampleSizeBytes = fixture.sample.size.toLong(),
            sampleFlags = 0x02000000L
        )
        val fragment = CmafBoxInspector.FragmentMetadata(
            trackId = trackId,
            timescale = 50_000L,
            baseDecodeTimeTicks = decodeTime,
            sampleCount = 1,
            totalDurationTicks = duration,
            sampleTimings = listOf(timing),
            timingComplete = true,
            sampleEncryptionBoxPresent = true,
            subsampleEncryptionSignalled = true,
            sampleEncryptionCount = 1L,
            sampleAuxiliarySizesPresent = false,
            sampleAuxiliaryOffsetsPresent = false,
            sampleGroupDescriptionPresent = false,
            sampleToGroupPresent = false,
            sampleEncryptionStructureComplete = true,
            sampleEncryptionMetadata = listOf(encryption),
            sampleEncryptionRangesCoverSamples = true,
            sampleDescriptionIndex = 1L,
            sampleGroupEncryptionStructureComplete = true
        )
        return ProtectedHevcPictureComposer.FeedSample(
            feedId = feedId,
            sampleBytes = fixture.sample,
            track = track,
            fragment = fragment,
            sampleIndex = sampleIndex,
            sourceTileIndex = sourceTileIndex,
            destinationAddress = destinationAddress,
            pocTracker = HevcPocTracker()
        )
    }
}
