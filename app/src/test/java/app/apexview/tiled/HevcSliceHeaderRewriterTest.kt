package app.apexview.tiled

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcSliceHeaderRewriterTest {
    @Test
    fun rewritesSinglePictureCellIntoOutputGridAndPreservesCabacRbsp() {
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val sourcePps = HevcParameterSets.parsePps(nal(34, ppsBits(0, tiles = false)), sourceSps)
        val outputPps = HevcParameterSets.parsePps(nal(34, ppsBits(1, tiles = true)), outputSps)

        assertFalse(sourcePps.tilesEnabled)
        assertTrue(outputPps.tilesEnabled)
        assertEquals(2, outputPps.numTileColumns)
        assertEquals(1, outputPps.numTileRows)

        val sourceNal = nal(1, pSliceBits() + byteArrayOf(
            0x12, 0x34, 0x00, 0x00, 0x01, 0x56, 0x78, 0x22
        ))
        val sourceHeader = HevcSliceHeaderParser.parse(sourceNal, sourceSps, sourcePps)
        val originalRbsp = HevcBitstream.ebspToRbsp(sourceNal.copyOfRange(2, sourceNal.size))
        val originalCabac = originalRbsp.copyOfRange(sourceHeader.bitsConsumed / 8, originalRbsp.size)

        val rewritten = HevcSliceHeaderRewriter.rewrite(
            nal = sourceNal,
            sourceSps = sourceSps,
            sourcePps = sourcePps,
            outputSps = outputSps,
            outputPps = outputPps,
            destinationAddress = 30
        )
        val outputHeader = HevcSliceHeaderParser.parse(rewritten.nal, outputSps, outputPps)
        val outputRbsp = HevcBitstream.ebspToRbsp(rewritten.nal.copyOfRange(2, rewritten.nal.size))
        val outputCabac = outputRbsp.copyOfRange(outputHeader.bitsConsumed / 8, outputRbsp.size)

        assertFalse(outputHeader.firstSliceSegmentInPicture)
        assertEquals(1, outputHeader.ppsId)
        assertEquals(30, outputHeader.sliceSegmentAddress)
        assertEquals(sourceHeader.picOrderCntLsb, outputHeader.picOrderCntLsb)
        assertEquals(sourceHeader.sliceType, outputHeader.sliceType)
        assertEquals(sourceHeader.references, outputHeader.references)
        assertArrayEquals(originalCabac, outputCabac)
        assertTrue(rewritten.copiedTailBytes > 0)
        assertArrayEquals(
            sourceNal.copyOfRange(rewritten.sourceTailOffset, sourceNal.size),
            rewritten.nal.copyOfRange(rewritten.nal.size - rewritten.copiedTailBytes, rewritten.nal.size)
        )
    }

    @Test
    fun rewritesLargeAllClearNalWithoutCloningItsCabacTail() {
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val sourcePps = HevcParameterSets.parsePps(nal(34, ppsBits(0, tiles = false)), sourceSps)
        val outputPps = HevcParameterSets.parsePps(nal(34, ppsBits(1, tiles = true)), outputSps)
        val cabac = ByteArray(128 * 1024) { index ->
            if (index % 193 in 10..12) 0 else (index % 251).toByte()
        }
        val sourceNal = nal(1, pSliceBits() + byteArrayOf(0x12, 0x34, 0x56) + cabac)
        val rewritten = HevcSliceHeaderRewriter.rewrite(
            sourceNal, sourceSps, sourcePps, outputSps, outputPps, destinationAddress = 30
        )
        val outputHeader = HevcSliceHeaderParser.parse(rewritten.nal, outputSps, outputPps)
        assertEquals(30, outputHeader.sliceSegmentAddress)
        assertTrue(rewritten.copiedTailBytes > 128 * 1024)
        assertArrayEquals(sourceNal.copyOfRange(rewritten.sourceTailOffset, sourceNal.size),
            rewritten.nal.copyOfRange(rewritten.nal.size - rewritten.copiedTailBytes, rewritten.nal.size))

        // A CMAF input range can begin/end anywhere in its backing fragment.
        // Adjacent NAL bytes must not be read, copied or modified.
        val padded = byteArrayOf(99, 98, 97) + sourceNal + byteArrayOf(96, 95)
        val expectedInput = padded.copyOf()
        val ranged = HevcSliceHeaderRewriter.rewrite(
            padded, sourceSps, sourcePps, outputSps, outputPps, destinationAddress = 30,
            clearNalBytes = sourceNal.size, sourceOffset = 3, sourceLength = sourceNal.size
        )
        assertArrayEquals(rewritten.nal, ranged.nal)
        assertEquals(rewritten.sourceTailOffset, ranged.sourceTailOffset)
        assertEquals(rewritten.copiedTailBytes, ranged.copiedTailBytes)
        assertArrayEquals(expectedInput, padded)
        val span = HevcBitstream.NalSpan(padded, 3, sourceNal.size)
        val parts = HevcSliceHeaderRewriter.rewriteClearParts(span, sourceSps, sourcePps,
            outputSps, outputPps, 30, null, HevcSliceHeaderParser.parse(sourceNal, sourceSps, sourcePps))
        try {
            assertArrayEquals(rewritten.nal, parts.materialize())
            val output = java.nio.ByteBuffer.allocate(parts.size)
            parts.writeTo(output)
            assertArrayEquals(rewritten.nal, output.array())
            assertEquals(30, parts.verifyHeader(outputSps, outputPps).sliceSegmentAddress)
            val contiguous = HevcClearCmafFragmentWriter.writeNalSamples(1, 2, 90000,
                listOf(HevcClearCmafFragmentWriter.NalSample(listOf(rewritten.nal), 3600, -120, 0x02000000L)),
                4, false, false)
            val direct = HevcClearCmafFragmentWriter.writeNalParts(1, 2, 90000,
                listOf(HevcClearCmafFragmentWriter.NalPartsSample(listOf(parts), 3600, -120, 0x02000000L)),
                4, false, false)
            assertArrayEquals(contiguous, direct)
            assertRejected("protected") {
                HevcClearCmafFragmentWriter.writeNalParts(1, 2, 90000,
                    listOf(HevcClearCmafFragmentWriter.NalPartsSample(listOf(parts), 3600, -120, 0x02000000L)),
                    4, true, false)
            }
            assertArrayEquals(expectedInput, padded)
        } finally { parts.close() }
        assertArrayEquals(expectedInput, padded)
        assertRejected("fully clear") {
            HevcSliceHeaderRewriter.rewrite(
                padded, sourceSps, sourcePps, outputSps, outputPps, 30,
                clearNalBytes = sourceNal.size - 1, sourceOffset = 3, sourceLength = sourceNal.size
            )
        }
    }

    @Test
    fun preservesTizenStylePerSliceDeblockingOverrideWhenRewritingCellHeader() {
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val sourcePps = HevcParameterSets.parsePps(
            nal(34, ppsBits(0, tiles = false, deblockingOverride = true)), sourceSps
        )
        val outputPps = HevcParameterSets.parsePps(
            nal(34, ppsBits(1, tiles = true, deblockingOverride = true)), outputSps
        )
        assertEquals(true, sourcePps.deblockingFilterOverrideEnabled)
        assertEquals(true, outputPps.deblockingFilterOverrideEnabled)

        val sourceNal = nal(
            1,
            pSliceWithDeblockingOverrideBits() + byteArrayOf(0x21, 0x32, 0x43, 0x54)
        )
        val sourceHeader = HevcSliceHeaderParser.parse(sourceNal, sourceSps, sourcePps)
        assertEquals(true, sourceHeader.deblockingFilterOverride)
        assertEquals(false, sourceHeader.deblockingFilterDisabled)
        assertEquals(2, sourceHeader.betaOffsetDiv2)
        assertEquals(-1, sourceHeader.tcOffsetDiv2)

        val rewritten = HevcSliceHeaderRewriter.rewrite(
            nal = sourceNal,
            sourceSps = sourceSps,
            sourcePps = sourcePps,
            outputSps = outputSps,
            outputPps = outputPps,
            destinationAddress = 30
        )
        val outputHeader = HevcSliceHeaderParser.parse(rewritten.nal, outputSps, outputPps)
        assertEquals(30, outputHeader.sliceSegmentAddress)
        assertEquals(sourceHeader.deblockingFilterOverride, outputHeader.deblockingFilterOverride)
        assertEquals(sourceHeader.deblockingFilterDisabled, outputHeader.deblockingFilterDisabled)
        assertEquals(sourceHeader.betaOffsetDiv2, outputHeader.betaOffsetDiv2)
        assertEquals(sourceHeader.tcOffsetDiv2, outputHeader.tcOffsetDiv2)

        val sourceRbsp = HevcBitstream.ebspToRbsp(sourceNal.copyOfRange(2, sourceNal.size))
        val outputRbsp = HevcBitstream.ebspToRbsp(rewritten.nal.copyOfRange(2, rewritten.nal.size))
        assertArrayEquals(
            sourceRbsp.copyOfRange(sourceHeader.bitsConsumed / 8, sourceRbsp.size),
            outputRbsp.copyOfRange(outputHeader.bitsConsumed / 8, outputRbsp.size)
        )
    }

    @Test
    fun makesDestinationAddressZeroTheOutputFirstSlice() {
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val sourcePps = HevcParameterSets.parsePps(nal(34, ppsBits(0, tiles = false)), sourceSps)
        val outputPps = HevcParameterSets.parsePps(nal(34, ppsBits(1, tiles = true)), outputSps)
        val sourceNal = nal(1, pSliceBits() + byteArrayOf(0x31, 0x22, 0x11, 0x45))

        val rewritten = HevcSliceHeaderRewriter.rewrite(
            sourceNal, sourceSps, sourcePps, outputSps, outputPps, destinationAddress = 0
        )
        val parsed = HevcSliceHeaderParser.parse(rewritten.nal, outputSps, outputPps)
        assertTrue(parsed.firstSliceSegmentInPicture)
        assertEquals(0, parsed.sliceSegmentAddress)
        assertEquals(1, parsed.ppsId)
    }

    @Test
    fun rejectsInsufficientClearPrefixAndOutOfRangeDestination() {
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val sourcePps = HevcParameterSets.parsePps(nal(34, ppsBits(0, tiles = false)), sourceSps)
        val outputPps = HevcParameterSets.parsePps(nal(34, ppsBits(1, tiles = true)), outputSps)
        val sourceNal = nal(1, pSliceBits() + byteArrayOf(0x31, 0x22, 0x11, 0x45))

        assertRejected("clear") {
            HevcSliceHeaderRewriter.rewrite(
                sourceNal, sourceSps, sourcePps, outputSps, outputPps, 30,
                clearNalBytes = 2
            )
        }
        assertRejected("destination") {
            HevcSliceHeaderRewriter.rewrite(
                sourceNal, sourceSps, sourcePps, outputSps, outputPps, 2000
            )
        }
    }

    @Test
    fun rejectsWppAndDependentSliceSegments() {
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val wppPps = HevcParameterSets.parsePps(
            nal(34, ppsBits(0, tiles = false, wpp = true)), sourceSps
        )
        val outputPps = HevcParameterSets.parsePps(nal(34, ppsBits(1, tiles = true)), outputSps)
        val sourceNal = nal(1, pSliceBits() + byteArrayOf(0x31, 0x22, 0x11, 0x45))
        assertRejected("WPP") {
            HevcSliceHeaderRewriter.rewrite(
                sourceNal, sourceSps, wppPps, outputSps, outputPps, 30
            )
        }
        val nonFirstDependent = BitWriter().apply {
            write(0, 1)
            writeUe(0)
            write(1, 1)
            write(1, 9)
        }.finish()
        val sourcePps = HevcParameterSets.parsePps(nal(34, ppsBits(0, tiles = false)), sourceSps)
        assertRejected("Dependent") {
            HevcSliceHeaderRewriter.rewrite(
                nal(1, nonFirstDependent), sourceSps, sourcePps, outputSps, outputPps, 30
            )
        }
    }

    @Test
    fun exportsClearSliceFeasibilityFixtures() {
        val root = System.getenv("PITWALL_G0_SLICE_DIR")?.let { java.io.File(it) } ?: return
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val sourcePps = HevcParameterSets.parsePps(nal(34, ppsBits(0, tiles = false)), sourceSps)
        val outputPps = HevcParameterSets.parsePps(nal(34, ppsBits(1, tiles = true)), outputSps)
        val ctbs = outputSps.picWidthInCtbs * outputSps.picHeightInCtbs
        val addressBits = 32 - Integer.numberOfLeadingZeros(ctbs - 1)
        for (size in listOf(16, 4096, 128 * 1024)) {
            val payload = ByteArray(size) { i -> if (i % 193 in 10..12) 0 else (i % 251).toByte() }
            val source = nal(1, pSliceBits() + byteArrayOf(0x12, 0x34, 0x56) + payload)
            val parsed = HevcSliceHeaderParser.parse(source, sourceSps, sourcePps)
            for (address in listOf(0,30)) {
                val dir = java.io.File(root, "bytes-$size-address-$address")
                check(dir.mkdirs() || dir.isDirectory)
                val span = HevcBitstream.NalSpan(source,0,source.size)
                fun rewrite() = HevcSliceHeaderRewriter.rewriteClearParts(span,sourceSps,sourcePps,
                    outputSps,outputPps,address,null,parsed)
                val expected = rewrite().use { it.materialize() }
                val out = ByteArray(expected.size)
                java.io.File(dir,"source.bin").writeBytes(source)
                java.io.File(dir,"expected.bin").writeBytes(expected)
                java.io.File(dir,"syntax.txt").writeText(listOf(parsed.sourceAddressEndBit,
                    parsed.entryPointFieldStartBit,parsed.entryPointFieldEndBit,parsed.alignmentStartBit,
                    parsed.bitsConsumed/8,address,addressBits,outputPps.ppsId,
                    if(outputPps.dependentSliceSegmentsEnabled) 1 else 0,
                    if(outputPps.tilesEnabled) 1 else 0,
                    if(parsed.noOutputOfPriorPictures == true) 1 else 0).joinToString(" ")+"\n")
                repeat(32) { rewrite().use { it.copyInto(out,0) } }
                val times=LongArray(101) {
                    val start=System.nanoTime()
                    repeat(16) { rewrite().use { it.copyInto(out,0) } }
                    (System.nanoTime()-start)/16
                }.sorted()
                assertArrayEquals(expected,out)
                println("G0 clear slice jvm case=${dir.name} bytes=${source.size} p50Ns=${times[50]} p95Ns=${times[95]} p99Ns=${times[99]} maxNs=${times[100]} prevalidatedSyntax=true")
            }
        }
    }

    @Test
    fun exportsAllEncodedTwoFeedSliceFixtures() {
        val root = System.getenv("PITWALL_G0_SLICE_DIR")?.let { java.io.File(it) } ?: return
        val fixture = java.io.File(requireNotNull(System.getenv("APEXVIEW_HEVC_FIXTURE_DIR")))
        val streams = listOf("feed_a.hevc","feed_b.hevc").map { java.io.File(fixture,it).readBytes() }
        val composed = HevcTizenGridComposer.composeAnnexBStreams(
            streams,HevcOutputGridDimensions(columns=2,rows=1),
            listOf(HevcTizenGridComposer.CellSelection(0,0,0,0),
                HevcTizenGridComposer.CellSelection(1,0,1,0)),
            frameRateNumerator=25,includeAnnexBOutput=false)
        assertFalse(composed.outputLoopFilterAcrossTilesEnabled)
        assertEquals(50,composed.pictures.size)
        val outputSps=HevcParameterSets.parseSps(composed.outputParameters.sps)
        val outputPps=HevcParameterSets.parsePps(composed.outputParameters.pps,outputSps)
        val addressBits=32-Integer.numberOfLeadingZeros(outputSps.picWidthInCtbs*outputSps.picHeightInCtbs-1)
        var verified=0
        streams.forEachIndexed { feed, stream ->
            val nals=HevcBitstream.splitAnnexB(stream)
            val sps=HevcParameterSets.parseSps(nals.first { it.type==33 }.bytes)
            val pps=HevcParameterSets.parsePps(nals.first { it.type==34 }.bytes,sps)
            val vcl=nals.filter { it.type in 0..21 }
            assertEquals(50,vcl.size)
            vcl.forEachIndexed { frame, unit ->
                val source=unit.bytes
                val parsed=HevcSliceHeaderParser.parse(source,sps,pps)
                val picture=composed.pictures[frame]
                val evidence=picture.evidence[feed]
                assertEquals(feed,evidence.feedIndex)
                val address=evidence.destinationCtbAddress
                val expected=picture.vclNals[feed]
                val span=HevcBitstream.NalSpan(source,0,source.size)
                fun rewrite()=HevcSliceHeaderRewriter.rewriteClearParts(span,sps,pps,outputSps,outputPps,address,null,parsed)
                assertArrayEquals(expected,rewrite().use { it.materialize() })
                val dir=java.io.File(root,"encoded-$feed-frame-$frame")
                check(dir.mkdirs() || dir.isDirectory)
                java.io.File(dir,"source.bin").writeBytes(source)
                java.io.File(dir,"expected.bin").writeBytes(expected)
                java.io.File(dir,"syntax.txt").writeText(listOf(parsed.sourceAddressEndBit,
                    parsed.entryPointFieldStartBit,parsed.entryPointFieldEndBit,parsed.alignmentStartBit,
                    parsed.bitsConsumed/8,address,addressBits,outputPps.ppsId,
                    if(outputPps.dependentSliceSegmentsEnabled) 1 else 0,
                    if(outputPps.tilesEnabled) 1 else 0,
                    if(parsed.noOutputOfPriorPictures==true) 1 else 0).joinToString(" ")+"\n")
                verified++
                if(frame<=1) {
                    val out=ByteArray(expected.size)
                    repeat(32) { rewrite().use { it.copyInto(out,0) } }
                    val times=LongArray(101) {
                        val start=System.nanoTime()
                        repeat(16) { rewrite().use { it.copyInto(out,0) } }
                        (System.nanoTime()-start)/16
                    }.sorted()
                    assertArrayEquals(expected,out)
                    println("G0 clear slice jvm case=${dir.name} bytes=${source.size} p50Ns=${times[50]} p95Ns=${times[95]} p99Ns=${times[99]} maxNs=${times[100]} prevalidatedSyntax=true")
                }
            }
        }
        assertEquals(100,verified)
    }

    private fun assertRejected(expectedText: String, action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected rejection containing: " + expectedText)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(expectedText, true))
        }
    }

    private fun spsBits(width: Int, height: Int): ByteArray = BitWriter().apply {
        write(1, 4); write(0, 3); write(1, 1)
        write(0, 8 + 32 + 4 + 44 + 8)
        writeUe(0); writeUe(1)
        writeUe(width); writeUe(height); write(0, 1)
        writeUe(0); writeUe(0); writeUe(4)
        write(1, 1); writeUe(4); writeUe(2); writeUe(0)
        writeUe(0); writeUe(3)
        writeUe(0); writeUe(0); writeUe(0); writeUe(0)
        write(0, 1) // scaling lists disabled
        write(0, 1) // AMP disabled
        write(1, 1) // SAO enabled
        write(0, 1) // PCM disabled
        writeUe(0) // no short-term reference picture sets
        write(0, 1) // long-term refs absent
        write(1, 1) // temporal MVP enabled
        write(1, 1) // strong smoothing
        write(0, 1) // no VUI
        write(0, 1) // no SPS extension
    }.finish()

    private fun ppsBits(id: Int, tiles: Boolean, wpp: Boolean = false, deblockingOverride: Boolean = false): ByteArray =
        BitWriter().apply {
            writeUe(id); writeUe(0)
            write(1, 1); write(0, 1); write(2, 3)
            write(0, 1); write(0, 1)
            writeUe(0); writeUe(0); writeSe(0)
            write(0, 1); write(0, 1); write(0, 1)
            writeSe(0); writeSe(0)
            write(0, 1); write(0, 1); write(0, 1); write(0, 1)
            write(if (tiles) 1 else 0, 1)
            write(if (wpp) 1 else 0, 1)
            if (tiles) {
                writeUe(1); writeUe(0); write(1, 1); write(1, 1)
            }
            write(1, 1) // loop_filter_across_slices_enabled_flag
            write(if (deblockingOverride) 1 else 0, 1) // deblocking_filter_control_present_flag
            if (deblockingOverride) {
                write(1, 1) // pps_deblocking_filter_override_enabled_flag
                write(0, 1) // pps_deblocking_filter_disabled_flag
                writeSe(0); writeSe(0) // default beta/tc offsets
            }
            write(0, 1) // pps_scaling_list_data_present_flag
            write(0, 1) // lists_modification_present_flag
            writeUe(0)
            write(0, 1) // slice_segment_header_extension_present_flag
            write(0, 1) // pps_extension_present_flag
        }.finish()

    private fun pSliceWithDeblockingOverrideBits(): ByteArray = BitWriter().apply {
        write(1, 1); writeUe(0); write(0, 2); writeUe(1)
        write(42, 8)
        write(0, 1); writeUe(2); writeUe(0)
        writeUe(0); write(1, 1); writeUe(2); write(0, 1)
        write(1, 1); write(1, 1); write(1, 1)
        write(0, 1); writeUe(0); writeSe(0)
        write(1, 1) // slice_deblocking_filter_override_flag
        write(0, 1) // slice_deblocking_filter_disabled_flag
        writeSe(2); writeSe(-1) // slice beta/tc offsets
        write(1, 1) // loop_filter_across_slices_enabled_flag
    }.finish()

    private fun pSliceBits(): ByteArray = BitWriter().apply {
        write(1, 1); writeUe(0); write(0, 2); writeUe(1)
        write(42, 8)
        write(0, 1); writeUe(2); writeUe(0)
        writeUe(0); write(1, 1); writeUe(2); write(0, 1)
        write(1, 1); write(1, 1); write(1, 1)
        write(0, 1); writeUe(0); writeSe(0)
        write(1, 1) // loop_filter_across_slices_enabled_flag
    }.finish()

    private fun nal(type: Int, rbsp: ByteArray): ByteArray {
        val header = byteArrayOf((type shl 1).toByte(), 1)
        return header + escape(rbsp)
    }

    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        var zeroCount = 0
        rbsp.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (zeroCount >= 2 && value <= 3) {
                out += 3.toByte()
                zeroCount = 0
            }
            out += byte
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun write(value: Int, count: Int) {
            for (index in count - 1 downTo 0) bits += (value ushr index) and 1
        }
        fun writeBit(value: Int) = write(value, 1)
        fun writeUe(value: Int) {
            val code = value.toLong() + 1L
            val width = 64 - java.lang.Long.numberOfLeadingZeros(code)
            repeat(width - 1) { bits += 0 }
            write(code.toInt(), width)
        }
        fun writeSe(value: Int) = writeUe(if (value <= 0) -2 * value else 2 * value - 1)
        fun finish(): ByteArray {
            bits += 1
            while (bits.size % 8 != 0) bits += 0
            return ByteArray(bits.size / 8) { index ->
                var value = 0
                for (bit in 0 until 8) value = (value shl 1) or bits[index * 8 + bit]
                value.toByte()
            }
        }
    }
}
