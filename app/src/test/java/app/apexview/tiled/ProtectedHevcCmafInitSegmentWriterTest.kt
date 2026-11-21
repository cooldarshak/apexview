package app.apexview.tiled

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Regression for Build 627's false CBCS protection metadata mutation alarm. */
class ProtectedHevcCmafInitSegmentWriterTest {
    @Test fun equalProtectionBoxesFromIndependentParsesAreAccepted() {
        val original = drmBoxes()
        ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(
            original, original.copyOf(), "bytewise preservation")
    }

    @Test fun alteredAndMissingTencPsshBoxesRemainRejected() {
        val original = drmBoxes()
        val modified = original.copyOf()
        val tencOffset = findType(modified, "tenc")
        modified[tencOffset + 11] = (modified[tencOffset + 11].toInt() xor 1).toByte()
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(
                original, modified, "mutated CBCS defaults")
        }.message!!.contains("sinf box 0 contents changed"))
        val modifiedPssh = original.copyOf()
        modifiedPssh[modifiedPssh.lastIndex] = 1
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(
                original, modifiedPssh, "mutated PSSH")
        }.message!!.contains("pssh box 0 contents changed"))
        val noPssh = original.copyOfRange(0, original.size - box("pssh", ByteArray(24)).size)
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(
                original, noPssh, "missing PSSH")
        }.message!!.contains("pssh box count changed"))
    }

    @Test fun protectedInitRewritePreservesCbcsHvc1AndHev1ForExpandedGrid() {
        val path = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("CI must provision generated Kvazaar tile fixtures", !path.isNullOrBlank())
        val file = File(path!!, "source_tiled_2x2.hevc")
        assumeTrue("Generated HEVC 2x2 fixture missing", file.isFile)
        val nals = HevcBitstream.splitAnnexB(file.readBytes())
        val vpsNal = nals.first { it.type == 32 }.bytes
        val spsNal = nals.first { it.type == 33 }.bytes
        val ppsNal = nals.first { it.type == 34 }.bytes
        val vps = HevcParameterSets.parseVps(vpsNal)
        val sps = HevcParameterSets.parseSps(spsNal)
        val pps = HevcParameterSets.parsePps(ppsNal, sps)
        val cols = requireNotNull(pps.tileColumnWidthsInCtbs)
        val rows = requireNotNull(pps.tileRowHeightsInCtbs)
        val filter = requireNotNull(pps.loopFilterAcrossTiles)
        fun output(width: Int, height: Int, columns: List<Int>, heights: List<Int>) =
            HevcOutputParameterSetWriter.generate(
                vpsNal, spsNal, ppsNal,
                HevcOutputParameterSetWriter.Request(
                    vps.vpsId, sps.spsId, pps.ppsId,
                    width, height, columns, heights, filter, 25,
                    uncroppedOutputPicture = true
                )
            )
        val sourceOutput = output(sps.picWidthInCtbs * sps.ctbSize,
            sps.picHeightInCtbs * sps.ctbSize, cols, rows)
        val atlas = output(sps.picWidthInCtbs * sps.ctbSize * 2,
            sps.picHeightInCtbs * sps.ctbSize * 2, cols + cols, rows + rows)
        val clear = HevcClearCmafInitSegmentWriter.write(
            HevcClearCmafInitSegmentWriter.Request(7L, 90_000L, sourceOutput))
        for (originalFormat in listOf("hvc1", "hev1")) {
            val protectedInit = protectInit(clear, originalFormat)
            val before = CmafBoxInspector.inspect(protectedInit)
            assertTrue(before.errors.toString(), before.errors.isEmpty())
            assertTrue(before.tracks.single().encrypted)
            val result = ProtectedHevcCmafInitSegmentWriter.rewrite(
                protectedInit, atlas, "generated protected 4-feed atlas")
            val after = CmafBoxInspector.inspect(result)
            assertTrue(after.errors.toString(), after.errors.isEmpty())
            val sourceTrack = before.tracks.single()
            val outputTrack = after.tracks.single()
            assertEquals("encv", outputTrack.sampleEntry)
            assertEquals(originalFormat, outputTrack.originalFormat)
            assertEquals("cbcs", outputTrack.protectionScheme)
            assertEquals(sourceTrack.trackEncryption, outputTrack.trackEncryption)
            assertEquals(sourceTrack.trackId, outputTrack.trackId)
            assertEquals(sourceTrack.timescale, outputTrack.timescale)
            assertEquals(HevcParameterSets.parseSps(atlas.sps).displayWidth, outputTrack.width)
            assertEquals(HevcParameterSets.parseSps(atlas.sps).displayHeight, outputTrack.height)
            ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(
                protectedInit, result, "independent CBCS output proof")
        }
    }

    private fun drmBoxes(): ByteArray =
        box("moov", box("sinf", box("schi", box("tenc",
            byteArrayOf(1, 0, 0, 0, 0, 0x19, 1, 16) + ByteArray(16) { 17.toByte() })))) +
            box("pssh", ByteArray(24))

    private fun protectInit(clear: ByteArray, originalFormat: String): ByteArray {
        val stsdType = findType(clear, "stsd")
        val entryStart = stsdType + 12
        val entrySize = readU32(clear, entryStart)
        val end = entryStart + entrySize
        require(end <= clear.size)
        val tenc = box("tenc", byteArrayOf(1, 0, 0, 0, 0, 0x19, 1, 16) +
            ByteArray(16) { 35.toByte() })
        val sinf = box("sinf",
            box("frma", ascii(originalFormat)) +
                box("schm", byteArrayOf(0, 0, 0, 0) + ascii("cbcs") + u32(0x00010000)) +
                box("schi", tenc))
        val altered = clear.copyOfRange(0, end) + sinf + clear.copyOfRange(end, clear.size)
        ascii("encv").copyInto(altered, entryStart + 4)
        writeU32(altered, entryStart, entrySize + sinf.size)
        listOf("moov", "trak", "mdia", "minf", "stbl", "stsd").forEach { name ->
            val boxStart = findType(clear, name) - 4
            writeU32(altered, boxStart, readU32(clear, boxStart) + sinf.size)
        }
        // Synthetic DRM metadata only: no credentials, keys or protected media.
        return altered + box("pssh", byteArrayOf(0, 0, 0, 0) + ByteArray(16) + u32(0))
    }

    private fun findType(data: ByteArray, name: String): Int {
        val needle = ascii(name)
        for (i in 4..(data.size - needle.size)) {
            if (needle.indices.all { data[i + it] == needle[it] }) return i
        }
        throw IllegalArgumentException("Missing synthetic box type " + name)
    }
    private fun box(type: String, body: ByteArray) = u32(body.size + 8) + ascii(type) + body
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun u32(i: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(i).array()
    private fun readU32(bytes: ByteArray, offset: Int) =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value)
    }
}
