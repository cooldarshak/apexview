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
            val fragment = HevcClearCmafFragmentWriter.write(
                trackId = index + 1L,
                sequenceNumber = 1,
                baseDecodeTimeTicks = 0,
                samples = samples,
                sourceTrackEncrypted = false,
                sourceFragmentHasEncryptionMetadata = false
            )
            HevcClearCmafGridComposer.Feed(init, listOf(fragment), "synthetic-feed-$index")
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
        assertEquals(1, output.fragmentCount)
        assertEquals(100, output.evidence.size)

        val report = CmafBoxInspector.inspect(
            output.initializationSegment + output.mediaFragments.single()
        )
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertEquals(1, report.tracks.size)
        assertEquals(9L, report.tracks.single().trackId)
        assertEquals("hvc1", report.tracks.single().sampleEntry)
        assertFalse(report.tracks.single().encrypted)
        assertEquals(50, report.fragments.single().sampleCount)
        assertEquals(180_000L, report.fragments.single().totalDurationTicks)
        assertTrue(report.fragments.single().timingComplete)

        val dashPackage = HevcComposedDashPackageBuilder.build(
            output.initializationSegment,
            output.mediaFragments
        )
        assertEquals(1920, dashPackage.width)
        assertEquals(540, dashPackage.height)
        assertEquals(50, dashPackage.sampleCount)
        assertEquals(output.fragmentCount, dashPackage.fragmentCount)
        assertEquals(3, dashPackage.resources.size)
        assertEquals(1, Regex("<Representation ").findAll(dashPackage.manifest).count())
        assertEquals(output.fragmentCount, Regex("<SegmentURL ").findAll(dashPackage.manifest).count())
        assertTrue(dashPackage.manifest.contains("codecs=\"hvc1\""))
        assertTrue(dashPackage.manifest.contains("sourceURL=\"init.mp4\""))
        assertFalse("Clear output must not advertise DRM protection", dashPackage.manifest.contains("ContentProtection"))

        val evidenceDirectory = System.getenv("APEXVIEW_HEVC_OUTPUT_PARAMETER_DIR")
            ?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("CI evidence directory is not configured", evidenceDirectory != null)
        assertTrue(evidenceDirectory!!.mkdirs() || evidenceDirectory.isDirectory)
        File(evidenceDirectory, "hevc_tizen_cmaf_grid.mpd").writeText(dashPackage.manifest)
        File(evidenceDirectory, "hevc_tizen_cmaf_grid.mp4").writeBytes(
            output.initializationSegment + output.mediaFragments.single()
        )
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
