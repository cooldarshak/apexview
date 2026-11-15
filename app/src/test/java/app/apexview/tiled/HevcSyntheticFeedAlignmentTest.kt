package app.apexview.tiled

import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HevcSyntheticFeedAlignmentTest {
    @Test
    fun parsesAndComparesRealFiftyFrameFfmpegSyntheticFeeds() {
        val fixtureDirectory = System.getenv("APEXVIEW_HEVC_FIXTURE_DIR")
        assumeTrue("CI synthetic HEVC fixture directory is not configured", !fixtureDirectory.isNullOrBlank())
        val directory = File(fixtureDirectory!!)
        val feedAFile = File(directory, "feed_a.hevc")
        val feedBFile = File(directory, "feed_b.hevc")
        assumeTrue("Synthetic HEVC feed pair is not present", feedAFile.isFile && feedBFile.isFile)

        val feedABytes = feedAFile.readBytes()
        val feedBBytes = feedBFile.readBytes()
        assertTrue("The two source encodes must be distinct bitstreams", !feedABytes.contentEquals(feedBBytes))
        val feedAHash = MessageDigest.getInstance("SHA-256").digest(feedABytes)
        val feedBHash = MessageDigest.getInstance("SHA-256").digest(feedBBytes)
        assertTrue("Independent feed SHA-256 hashes must differ", !feedAHash.contentEquals(feedBHash))

        val feedAParameterSets = parseSourceParameterSets(feedABytes)
        val feedBParameterSets = parseSourceParameterSets(feedBBytes)
        // HEVC codes complete CTBs, so the coded height is padded to 544.
        // The SPS conformance window must expose the intended 960x540 display rectangle.
        assertEquals(960, feedAParameterSets.sps.displayWidth)
        assertEquals(540, feedAParameterSets.sps.displayHeight)
        assertEquals(960, feedBParameterSets.sps.displayWidth)
        assertEquals(540, feedBParameterSets.sps.displayHeight)
        assertEquals(544, feedAParameterSets.sps.height)
        assertEquals(544, feedBParameterSets.sps.height)
        assertTrue("Feed A must be a 1x1 source picture", !feedAParameterSets.pps.tilesEnabled)
        assertTrue("Feed B must be a 1x1 source picture", !feedBParameterSets.pps.tilesEnabled)

        val feedA = HevcStreamAlignmentAnalyzer.analyze(feedABytes)
        val feedB = HevcStreamAlignmentAnalyzer.analyze(feedBBytes)
        val report = HevcStreamAlignmentAnalyzer.compare(feedA, feedB)

        assertEquals(50, feedA.size)
        assertEquals(50, feedB.size)
        assertTrue("Synthetic feeds should have identical POC sequences", report.pocAligned)
        assertTrue("Synthetic feeds should have identical slice types and RPS", report.referencesAligned)
        assertTrue(
            "Every synthetic frame signature should align. First differences: " +
                report.mismatches.take(5).joinToString { mismatch ->
                    val first = feedA[mismatch.decodeIndex].pictureIdentity
                    val second = feedB[mismatch.decodeIndex].pictureIdentity
                    "index=${mismatch.decodeIndex} type=${first.nalUnitType}/${second.nalUnitType} " +
                        "pps=${first.ppsId}/${second.ppsId} noOutput=${first.noOutputOfPriorPictures}/${second.noOutputOfPriorPictures} " +
                        "sliceType=${first.sliceType}/${second.sliceType} picOutput=${first.picOutputFlag}/${second.picOutputFlag} " +
                        "colourPlane=${first.colourPlaneId}/${second.colourPlaneId} pocLsb=${first.picOrderCntLsb}/${second.picOrderCntLsb} " +
                        "rps=${first.shortTermRpsFromSps}:${first.shortTermRpsIndex}/${second.shortTermRpsFromSps}:${second.shortTermRpsIndex}"
                },
            report.fullyAligned
        )
        assertTrue("P frames should expose short-term references", feedA.drop(1).any { it.references.isNotEmpty() })
    }

    @Test
    fun reidentifiesOneFeedPpsAndEverySliceReferenceWithoutChangingCabacPayload() {
        val fixtureDirectory = System.getenv("APEXVIEW_HEVC_FIXTURE_DIR")
        assumeTrue("CI synthetic HEVC fixture directory is not configured", !fixtureDirectory.isNullOrBlank())
        val directory = File(fixtureDirectory!!)
        val firstFile = File(directory, "feed_a.hevc")
        val secondFile = File(directory, "feed_b.hevc")
        assumeTrue("Synthetic HEVC feed pair is not present", firstFile.isFile && secondFile.isFile)

        val firstBytes = firstFile.readBytes()
        val secondBytes = secondFile.readBytes()
        val secondSets = parseSourceParameterSets(secondBytes)
        val reidentified = rewriteFeedPpsId(secondBytes, secondSets.sps, secondSets.pps, 1)
        val reidentifiedSets = parseSourceParameterSets(reidentified)
        assertEquals(0, parseSourceParameterSets(firstBytes).pps.ppsId)
        assertEquals(1, reidentifiedSets.pps.ppsId)
        assertEquals(reidentifiedSets.sps.spsId, reidentifiedSets.pps.spsId)
        assertEquals(reidentifiedSets.vps.vpsId, reidentifiedSets.sps.vpsId)

        val parsedSlices = HevcBitstream.splitAnnexB(reidentified)
            .filter { it.type <= 31 }
            .map { HevcSliceHeaderParser.parse(it.bytes, reidentifiedSets.sps, reidentifiedSets.pps) }
        assertEquals(50, parsedSlices.size)
        assertTrue("Every rewritten slice must reference the rewritten PPS", parsedSlices.all { it.ppsId == 1 })

        val outputA = File(directory, "feed_a_pps0.hevc")
        val outputB = File(directory, "feed_b_pps1.hevc")
        outputA.writeBytes(firstBytes)
        outputB.writeBytes(reidentified)
    }

    private data class ParameterSets(
        val vps: HevcParameterSets.VpsMetadata,
        val sps: HevcParameterSets.SpsMetadata,
        val pps: HevcParameterSets.PpsMetadata
    )

    private fun parseSourceParameterSets(annexB: ByteArray): ParameterSets {
        val nals = HevcBitstream.splitAnnexB(annexB)
        val vpsNal = nals.first { it.type == 32 }.bytes
        val spsNal = nals.first { it.type == 33 }.bytes
        val ppsNal = nals.first { it.type == 34 }.bytes
        val vps = HevcParameterSets.parseVps(vpsNal)
        val sps = HevcParameterSets.parseSps(spsNal)
        val pps = HevcParameterSets.parsePps(ppsNal, sps)
        assertEquals("SPS must reference the source VPS", vps.vpsId, sps.vpsId)
        assertEquals("PPS must reference the source SPS", sps.spsId, pps.spsId)
        return ParameterSets(vps, sps, pps)
    }

    private fun rewriteFeedPpsId(
        annexB: ByteArray,
        sps: HevcParameterSets.SpsMetadata,
        pps: HevcParameterSets.PpsMetadata,
        outputPpsId: Int
    ): ByteArray {
        val result = ByteArrayOutputStream(annexB.size + 64)
        HevcBitstream.splitAnnexB(annexB).forEach { unit ->
            val rewritten = when (unit.type) {
                34 -> rewriteParameterSetPpsId(unit.bytes, outputPpsId)
                in 0..31 -> rewriteSlicePpsId(unit.bytes, sps, pps, outputPpsId)
                else -> unit.bytes
            }
            result.write(byteArrayOf(0, 0, 0, 1))
            result.write(rewritten)
        }
        return result.toByteArray()
    }

    private fun rewriteParameterSetPpsId(nal: ByteArray, outputPpsId: Int): ByteArray {
        val rbsp = HevcBitstream.ebspToRbsp(nal.copyOfRange(2, nal.size))
        val allBits = bytesToBits(rbsp)
        val stop = allBits.indexOfLast { it == 1 }
        require(stop >= 0) { "PPS is missing rbsp_stop_one_bit" }
        val syntax = allBits.subList(0, stop)
        val (oldId, end) = readUe(syntax, 0)
        require(oldId in 0..63) { "Source PPS id is out of range" }
        val rebuilt = mutableListOf<Int>()
        rebuilt += syntax.subList(0, 0)
        rebuilt += encodeUe(outputPpsId)
        rebuilt += syntax.subList(end, syntax.size)
        return nal.copyOfRange(0, 2) + escapeRbsp(bitsToBytes(withTrailingBits(rebuilt)))
    }

    private fun rewriteSlicePpsId(
        nal: ByteArray,
        sps: HevcParameterSets.SpsMetadata,
        pps: HevcParameterSets.PpsMetadata,
        outputPpsId: Int
    ): ByteArray {
        val parsed = HevcSliceHeaderParser.parse(nal, sps, pps)
        val header = HevcBitstream.parseNalHeader(nal)
        val rbsp = HevcBitstream.ebspToRbsp(nal.copyOfRange(2, nal.size))
        val allBits = bytesToBits(rbsp)
        var ppsStart = 1 // first_slice_segment_in_pic_flag
        if (header.type in 16..21) ppsStart++ // no_output_of_prior_pics_flag
        val (oldId, ppsEnd) = readUe(allBits, ppsStart)
        require(oldId == pps.ppsId && parsed.ppsId == oldId) {
            "Slice parameter-set reference does not match its source PPS"
        }
        val headerEnd = parsed.bitsConsumed
        require(headerEnd > ppsEnd && headerEnd % 8 == 0 && headerEnd <= allBits.size) {
            "Parsed slice-header byte alignment is invalid"
        }

        // bitsConsumed ends at byte_alignment(). Remove its old alignment marker, insert the new
        // PPS Exp-Golomb code, and calculate fresh alignment. CABAC bytes after the old aligned
        // header are copied verbatim.
        val alignmentStart = (headerEnd - 1 downTo ppsEnd).firstOrNull { allBits[it] == 1 }
            ?: throw IllegalArgumentException("Slice header is missing alignment_bit_equal_to_one")
        val headerBits = mutableListOf<Int>()
        headerBits += allBits.subList(0, ppsStart)
        headerBits += encodeUe(outputPpsId)
        headerBits += allBits.subList(ppsEnd, alignmentStart)
        headerBits += 1
        while (headerBits.size % 8 != 0) headerBits += 0
        val cabacPayload = rbsp.copyOfRange(headerEnd / 8, rbsp.size)
        val rewrittenRbsp = bitsToBytes(headerBits) + cabacPayload
        return nal.copyOfRange(0, 2) + escapeRbsp(rewrittenRbsp)
    }

    private fun readUe(bits: List<Int>, start: Int): Pair<Int, Int> {
        var position = start
        var leadingZeros = 0
        while (true) {
            require(position < bits.size) { "Truncated Exp-Golomb value" }
            if (bits[position++] == 1) break
            leadingZeros++
            require(leadingZeros <= 30) { "Exp-Golomb value exceeds supported range" }
        }
        var suffix = 0
        repeat(leadingZeros) {
            require(position < bits.size) { "Truncated Exp-Golomb suffix" }
            suffix = (suffix shl 1) or bits[position++]
        }
        val value = ((1L shl leadingZeros) - 1L + suffix).also {
            require(it <= Int.MAX_VALUE) { "Exp-Golomb value exceeds Int range" }
        }.toInt()
        return value to position
    }

    private fun encodeUe(value: Int): List<Int> {
        require(value >= 0)
        val codeNum = value.toLong() + 1L
        val width = 64 - java.lang.Long.numberOfLeadingZeros(codeNum)
        return List(width - 1) { 0 } + (width - 1 downTo 0).map { ((codeNum ushr it) and 1L).toInt() }
    }

    private fun withTrailingBits(syntax: List<Int>): List<Int> {
        val result = syntax.toMutableList()
        result += 1
        while (result.size % 8 != 0) result += 0
        return result
    }

    private fun bytesToBits(bytes: ByteArray): List<Int> = buildList(bytes.size * 8) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            for (bit in 7 downTo 0) add((value ushr bit) and 1)
        }
    }

    private fun bitsToBytes(bits: List<Int>): ByteArray {
        require(bits.size % 8 == 0) { "Bit sequence must be byte aligned" }
        val result = ByteArray(bits.size / 8)
        bits.forEachIndexed { index, bit ->
            if (bit != 0) {
                val byteIndex = index / 8
                result[byteIndex] = (result[byteIndex].toInt() or (1 shl (7 - index % 8))).toByte()
            }
        }
        return result
    }

    private fun escapeRbsp(rbsp: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(rbsp.size + 16)
        var zeroCount = 0
        rbsp.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (zeroCount >= 2 && value <= 3) {
                output.write(3)
                zeroCount = 0
            }
            output.write(value)
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return output.toByteArray()
    }
}
