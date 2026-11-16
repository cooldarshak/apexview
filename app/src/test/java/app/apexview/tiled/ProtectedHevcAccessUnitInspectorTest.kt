package app.apexview.tiled

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProtectedHevcAccessUnitInspectorTest {
    @Test fun inspectsFourTilePictureUsingOnlyClearPrefixesOfEncryptedSamples() {
        val fixture = firstFourTilePicture()
        val sample = lengthPrefixedSample(fixture.sliceNals, encryptedTailBytes = 1)
        val layout = ProtectedHevcSampleLayout.inspect(sample.bytes, 4, sample.ranges)

        val report = ProtectedHevcAccessUnitInspector.inspectLengthPrefixedSample(
            sample = sample.bytes,
            layout = layout,
            sps = fixture.sps,
            pps = fixture.pps,
            context = "synthetic protected four-tile picture"
        )

        assertEquals(4, report.slices.size)
        assertEquals(listOf(0, 1, 2, 3), report.slices.map { it.tile.index })
        assertEquals(listOf(0, 2, 4, 6), report.slices.map { it.header.sliceSegmentAddress })
        assertEquals(4, report.sourceTileCount)
        assertTrue(report.independentSliceEligible)
        assertTrue(report.slicesReadyForCurrentHeaderRewriter)
        assertTrue(report.slices.all { it.clearCabacBytes >= 3 })
        assertTrue(report.slices.all { it.thirdClearCabacByteOffset != null })
        assertTrue(report.nals.all { !it.fullyClear })
        if (report.isIdrPicture) assertEquals(0, report.pictureOrderCountLsb)
    }

    @Test fun protectedSliceRewriterPreservesCiphertextAndRebuildsExactNalProtectionMap() {
        val fixture = firstFourTilePicture()
        val sample = lengthPrefixedSample(fixture.sliceNals, encryptedTailBytes = 1)
        val layout = ProtectedHevcSampleLayout.inspect(sample.bytes, 4, sample.ranges)
        val accessUnit = ProtectedHevcAccessUnitInspector.inspectLengthPrefixedSample(
            sample.bytes, layout, fixture.sps, fixture.pps,
            context = "synthetic protected slice rewrite"
        )

        assertEquals(listOf(0, 2, 4, 6), accessUnit.slices.map { it.header.sliceSegmentAddress })
        accessUnit.slices.forEachIndexed { index, slice ->
            val mapped = layout.nals.single { it.index == slice.nalIndex }
            val sourceNal = sample.bytes.copyOfRange(
                mapped.nalOffset, mapped.nalOffset + mapped.nalLength
            )
            val rewritten = ProtectedHevcSliceHeaderRewriter.rewrite(
                sample = sample.bytes,
                layout = layout,
                accessUnit = accessUnit,
                nalIndex = slice.nalIndex,
                sourceSps = fixture.sps,
                sourcePps = fixture.pps,
                outputSps = fixture.sps,
                outputPps = fixture.pps,
                destinationAddress = listOf(0, 2, 4, 6)[index],
                context = "synthetic protected tile $index"
            )

            assertEquals(listOf(0, 2, 4, 6)[index], rewritten.destinationAddress)
            assertEquals(slice.thirdClearCabacByteOffset, rewritten.sourceTailOffset)
            assertArrayEquals(
                sourceNal.copyOfRange(rewritten.sourceTailOffset, sourceNal.size),
                rewritten.nal.copyOfRange(rewritten.outputTailOffset, rewritten.nal.size)
            )
            assertEquals(1L, rewritten.encryptedByteCount)
            assertEquals(1, rewritten.protectionRanges.filterNot { it.isClear }.sumOf { it.lengthBytes })
            assertFalse(rewritten.protectionRanges.last().isClear)
            assertEquals(0xA5.toByte(), rewritten.nal.last())
            assertEquals(rewritten.nal.size, rewritten.protectionRanges.sumOf { it.lengthBytes })
            assertEquals(0, rewritten.protectionRanges.first().offset)
            assertEquals(rewritten.nal.size, rewritten.protectionRanges.last().endExclusive)
        }
    }

    @Test fun rejectsSliceHeaderWhenSubsampleMapExposesOnlyNalHeaderAndOnePayloadByte() {
        val fixture = firstFourTilePicture()
        val sample = lengthPrefixedSample(fixture.sliceNals, encryptedTailBytes = 0)
        val ranges = fixture.sliceNals.map { nal ->
            CmafBoxInspector.SubsampleEncryptionRange(
                clearBytes = 4 + 3,
                encryptedBytes = (nal.size - 3).toLong()
            )
        }
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            val layout = ProtectedHevcSampleLayout.inspect(sample.bytes, 4, ranges)
            ProtectedHevcAccessUnitInspector.inspectLengthPrefixedSample(
                sample.bytes, layout, fixture.sps, fixture.pps,
                context = "truncated protected slice prefix"
            )
        }
        assertTrue(error.message!!.contains("clear slice header is incomplete or unsupported"))
    }

    private data class Fixture(
        val sps: HevcParameterSets.SpsMetadata,
        val pps: HevcParameterSets.PpsMetadata,
        val sliceNals: List<ByteArray>
    )

    private data class ProtectedSample(
        val bytes: ByteArray,
        val ranges: List<CmafBoxInspector.SubsampleEncryptionRange>
    )

    private fun firstFourTilePicture(): Fixture {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Kvazaar 2x2 tile fixture directory is not configured", !fixturePath.isNullOrBlank())
        val source = File(fixturePath!!, "source_tiled_2x2.hevc")
        assumeTrue("Synthetic 2x2 HEVC source stream is missing", source.isFile)

        val nals = HevcBitstream.splitAnnexB(source.readBytes())
        val spsNal = nals.single { it.type == 33 }.bytes
        val ppsNal = nals.single { it.type == 34 }.bytes
        val sps = HevcParameterSets.parseSps(spsNal)
        val pps = HevcParameterSets.parsePps(ppsNal, sps)
        val firstVcl = nals.indexOfFirst { it.type in 0..31 &&
            (it.bytes[2].toInt() and 0x80) != 0 }
        assumeTrue("Synthetic stream contains no first slice", firstVcl >= 0)
        val nextPicture = (firstVcl + 1 until nals.size).firstOrNull { index ->
            val nal = nals[index]
            nal.type in 0..31 && (nal.bytes[2].toInt() and 0x80) != 0
        } ?: nals.size
        val slices = nals.subList(firstVcl, nextPicture)
            .filter { it.type in 0..31 }
            .map { it.bytes }
        assertEquals(4, slices.size)
        assertEquals(2, pps.numTileColumns)
        assertEquals(2, pps.numTileRows)
        return Fixture(sps, pps, slices)
    }

    private fun lengthPrefixedSample(
        nals: List<ByteArray>,
        encryptedTailBytes: Int
    ): ProtectedSample {
        val output = ByteArrayOutputStream()
        val ranges = mutableListOf<CmafBoxInspector.SubsampleEncryptionRange>()
        nals.forEach { nal ->
            require(nal.size >= 3 + encryptedTailBytes)
            output.write(byteArrayOf(
                (nal.size ushr 24).toByte(),
                (nal.size ushr 16).toByte(),
                (nal.size ushr 8).toByte(),
                nal.size.toByte()
            ))
            val clearNalBytes = nal.size - encryptedTailBytes
            output.write(nal, 0, clearNalBytes)
            if (encryptedTailBytes > 0) {
                // Deterministic stand-in bytes exercise the clear/encrypted boundary. They are
                // never passed to a parser or decrypted by the test.
                output.write(ByteArray(encryptedTailBytes) { 0xA5.toByte() })
            }
            ranges += CmafBoxInspector.SubsampleEncryptionRange(
                clearBytes = 4 + clearNalBytes,
                encryptedBytes = encryptedTailBytes.toLong()
            )
        }
        return ProtectedSample(output.toByteArray(), ranges)
    }
}
