package app.apexview.tiled

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HevcClearCmafGridComposerTest {
    @Test
    fun sizesBoundedAnnexBSamplesWithoutCopyingWholeFragments() {
        val twoBytePrefixes = byteArrayOf(77, 0, 3, 11, 22, 33, 0, 2, 44, 55, 88)
        assertEquals(13, HevcClearCmafGridComposer.annexBSizeForSample(
            twoBytePrefixes, offset = 1, sampleLength = 9, lengthSize = 2))
        assertEquals(13, HevcClearCmafGridComposer.annexBSizeForSample(
            byteArrayOf(2, 11, 12, 3, 13, 14, 15), 0, 7, 1))
        assertEquals(6, HevcClearCmafGridComposer.annexBSizeForSample(
            byteArrayOf(0, 0, 2, 9, 10), 0, 5, 3))
        assertEquals(6, HevcClearCmafGridComposer.annexBSizeForSample(
            byteArrayOf(0, 0, 0, 2, 9, 10), 0, 6, 4))
    }

    @Test
    fun rejectsMalformedLengthPrefixesAndOutOfBoundsSamples() {
        fun invalid(bytes: ByteArray, offset: Int, length: Int, prefix: Int) =
            runCatching { HevcClearCmafGridComposer.annexBSizeForSample(bytes, offset, length, prefix) }.isFailure
        assertTrue(invalid(byteArrayOf(0, 4, 9, 10), 0, 4, 2))
        assertTrue(invalid(byteArrayOf(0), 0, 1, 2))
        assertTrue(invalid(byteArrayOf(0, 2, 9, 10), 2, 3, 2))
        assertTrue(invalid(byteArrayOf(0, 2, 9, 10), 0, 4, 5))
    }

    @Test
    fun composesTwoTimedClearCmafFeedsIntoOneGridTrack() {
        val fixtureDir = System.getenv("APEXVIEW_HEVC_FIXTURE_DIR")
            ?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("Independent HEVC source fixtures are not configured", fixtureDir != null)

        val feeds = listOf("feed_a.hevc", "feed_b.hevc").mapIndexed { index, name ->
            val stream = File(fixtureDir!!, name).readBytes()
            val nals = HevcBitstream.splitAnnexB(stream)
            val vps = nals.first { it.type == 32 }.bytes
            val sps = nals.first { it.type == 33 }.bytes
            val pps = nals.first { it.type == 34 }.bytes
            val init = HevcClearCmafInitSegmentWriter.writeSource(
                trackId = index + 1L,
                mediaTimescale = 90_000,
                vpsNal = vps,
                spsNal = sps,
                ppsNal = pps
            )
            val pictures = splitPictures(nals)
            assertEquals(50, pictures.size)
            val samples = pictures.map { picture ->
                val firstType = HevcBitstream.parseNalHeader(picture.first()).type
                HevcClearCmafFragmentWriter.Sample(
                    data = lengthPrefix(picture),
                    durationTicks = 3_600,
                    compositionTimeOffsetTicks = 0,
                    sampleFlags = if (firstType in 16..23) 0x02000000L else 0x01010000L
                )
            }
            val fragments = samples.chunked(25).mapIndexed { fragmentIndex, fragmentSamples ->
                HevcClearCmafFragmentWriter.write(
                    trackId = index + 1L,
                    sequenceNumber = fragmentIndex.toLong() + 1L,
                    baseDecodeTimeTicks = fragmentIndex.toLong() * 25L * 3_600L,
                    samples = fragmentSamples,
                    sourceTrackEncrypted = false,
                    sourceFragmentHasEncryptionMetadata = false
                )
            }
            assertEquals(2, fragments.size)
            HevcClearCmafGridComposer.Feed(init, fragments, "synthetic-feed-$index")
        }

        val output = HevcClearCmafGridComposer.compose(
            HevcClearCmafGridComposer.Request(
                feeds = feeds,
                outputGrid = HevcOutputGridDimensions(columns = 2, rows = 1),
                cells = listOf(
                    HevcTizenGridComposer.CellSelection(0, 0, 0, 0),
                    HevcTizenGridComposer.CellSelection(1, 0, 1, 0)
                ),
                outputTrackId = 9,
                frameRateNumerator = 25
            )
        )

        assertEquals(1920, output.codedWidth)
        assertEquals(544, output.codedHeight)
        assertEquals(1920, output.displayWidth)
        assertEquals(540, output.displayHeight)
        assertEquals(50, output.sampleCount)
        assertEquals(2, output.fragmentCount)
        assertEquals(100, output.evidence.size)

        // A session validates each exact rendition's init once. Reusing that immutable
        // metadata must not change a single output byte or weaken fragment checks.
        val cachedOutput = HevcClearCmafGridComposer.compose(
            HevcClearCmafGridComposer.Request(
                feeds = feeds.map { it.copy(validatedInitialization = CmafBoxInspector.inspect(it.initializationSegment)) },
                outputGrid = HevcOutputGridDimensions(columns = 2, rows = 1),
                cells = listOf(
                    HevcTizenGridComposer.CellSelection(0, 0, 0, 0),
                    HevcTizenGridComposer.CellSelection(1, 0, 1, 0)
                ),
                outputTrackId = 9,
                frameRateNumerator = 25
            )
        )
        assertTrue(output.initializationSegment.contentEquals(cachedOutput.initializationSegment))
        output.mediaFragments.zip(cachedOutput.mediaFragments).forEach { (actual, cached) ->
            assertTrue(actual.contentEquals(cached))
        }

        val composedBytes = output.mediaFragments.fold(output.initializationSegment) { acc, fragment ->
            acc + fragment
        }
        val report = CmafBoxInspector.inspect(composedBytes)
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertEquals(1, report.tracks.size)
        assertEquals(9L, report.tracks.single().trackId)
        assertEquals("hvc1", report.tracks.single().sampleEntry)
        assertFalse(report.tracks.single().encrypted)
        assertEquals(2, report.fragments.size)
        assertEquals(listOf(0L, 90_000L), report.fragments.map { it.baseDecodeTimeTicks })
        assertEquals(listOf(25, 25), report.fragments.map { it.sampleCount })
        assertEquals(listOf(90_000L, 90_000L), report.fragments.map { it.totalDurationTicks })
        assertTrue(report.fragments.all { it.timingComplete })

        val dashPackage = HevcComposedDashPackageBuilder.build(
            output.initializationSegment,
            output.mediaFragments
        )
        assertEquals(1920, dashPackage.width)
        assertEquals(540, dashPackage.height)
        assertEquals(50, dashPackage.sampleCount)
        assertEquals(output.fragmentCount, dashPackage.fragmentCount)
        assertEquals(4, dashPackage.resources.size)
        assertEquals(1, Regex("<Representation ").findAll(dashPackage.manifest).count())
        assertEquals(2, Regex("<SegmentURL ").findAll(dashPackage.manifest).count())
        assertTrue(dashPackage.resources.containsKey("segment-0.m4s"))
        assertTrue(dashPackage.resources.containsKey("segment-1.m4s"))
        assertTrue(dashPackage.manifest.contains("codecs=\"hvc1\""))
        assertTrue("MPD duration must be valid ISO-8601 seconds",
            dashPackage.manifest.contains("mediaPresentationDuration=\"PT2S\""))
        assertTrue("Period duration must be valid ISO-8601 seconds",
            dashPackage.manifest.contains("duration=\"PT2S\""))
        assertTrue(dashPackage.manifest.contains("sourceURL=\"init.mp4\""))
        assertFalse("Clear output must not advertise DRM protection", dashPackage.manifest.contains("ContentProtection"))

        val evidenceDirectory = System.getenv("APEXVIEW_HEVC_OUTPUT_PARAMETER_DIR")
            ?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("CI evidence directory is not configured", evidenceDirectory != null)
        assertTrue(evidenceDirectory!!.mkdirs() || evidenceDirectory.isDirectory)
        File(evidenceDirectory, "hevc_tizen_cmaf_grid.mpd").writeText(dashPackage.manifest)
        File(evidenceDirectory, "hevc_tizen_cmaf_grid.mp4").writeBytes(composedBytes)
    }

    private fun splitPictures(nals: List<HevcBitstream.NalUnit>): List<List<ByteArray>> {
        val result = mutableListOf<List<ByteArray>>()
        var current = mutableListOf<ByteArray>()
        nals.filter { it.type in 0..31 }.forEach { nal ->
            require(nal.bytes.size >= 3) { "Truncated VCL NAL" }
            val first = (nal.bytes[2].toInt() and 0x80) != 0
            if (first) {
                if (current.isNotEmpty()) result += current
                current = mutableListOf()
            } else {
                require(current.isNotEmpty()) { "Continuation slice before first slice" }
            }
            current += nal.bytes
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun lengthPrefix(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        nals.forEach { nal ->
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(nal.size).array())
            out.write(nal)
        }
        return out.toByteArray()
    }
}
