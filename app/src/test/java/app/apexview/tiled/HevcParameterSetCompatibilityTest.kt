package app.apexview.tiled

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcParameterSetCompatibilityTest {
    @Test fun acceptsIdenticalFullyRepresentedTiledParameterSets() {
        val first = fixture()
        val second = fixture()
        val result = HevcParameterSetCompatibility.compare(
            first.vps, first.sps, first.pps, second.vps, second.sps, second.pps
        )
        assertTrue(result.reasons.toString(), result.compatible)
    }

    @Test fun rejectsAnySpsProfileOrPpsGeometryDifference() {
        val first = fixture()
        val changedProfile = first.sps.copy(
            profileTierLevel = first.sps.profileTierLevel.copy(levelIdc = 120)
        )
        val profileResult = HevcParameterSetCompatibility.compare(
            first.vps, first.sps, first.pps, first.vps, changedProfile, first.pps
        )
        assertFalse(profileResult.compatible)
        assertTrue(profileResult.reasons.any { it.contains("SPS metadata differs") })

        val changedGeometry = first.pps.copy(numTileColumns = first.pps.numTileColumns + 1)
        val geometryResult = HevcParameterSetCompatibility.compare(
            first.vps, first.sps, first.pps, first.vps, first.sps, changedGeometry
        )
        assertFalse(geometryResult.compatible)
        assertTrue(geometryResult.reasons.any { it.contains("PPS metadata differs") })
    }

    @Test fun rejectsSameParameterSetsWhenTileGeometryIsOutsideTheAssemblerProfile() {
        val f = fixture()
        val fourColumns = f.pps.copy(
            numTileColumns = 4,
            tileColumnWidthsInCtbs = listOf(5, 5, 5, 5)
        )
        val fourColumnResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, fourColumns, f.vps, f.sps, fourColumns
        )
        assertFalse(fourColumnResult.compatible)
        assertTrue(fourColumnResult.reasons.any { it.contains("2-column by 1-row") })

        val unevenColumns = f.pps.copy(tileColumnWidthsInCtbs = listOf(8, 12))
        val unevenResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, unevenColumns, f.vps, f.sps, unevenColumns
        )
        assertFalse(unevenResult.compatible)
        assertTrue(unevenResult.reasons.any { it.contains("uniform, full-picture 2x1") })

        val missingGeometry = f.pps.copy(tileColumnWidthsInCtbs = null)
        val missingResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, missingGeometry, f.vps, f.sps, missingGeometry
        )
        assertFalse(missingResult.compatible)
        assertTrue(missingResult.reasons.any { it.contains("tile geometry is missing") })
    }

    @Test fun acceptsIdenticalParsedVuiAndRejectsVuiOrSpsExtensionDifferences() {
        val f = fixture()
        val vuiMetadata = HevcParameterSets.VuiMetadata(
            aspectRatioIdc = null,
            sarWidth = null,
            sarHeight = null,
            videoFormat = 5,
            videoFullRange = true,
            colourPrimaries = 1,
            transferCharacteristics = 2,
            matrixCoefficients = 3,
            timingInfoPresent = true,
            numUnitsInTick = 1,
            timeScale = 25,
            hrdParametersPresent = false,
            bitstreamRestrictionPresent = false
        )
        val vui = f.sps.copy(vuiParametersPresent = true, vuiMetadata = vuiMetadata)
        val identicalVuiResult = HevcParameterSetCompatibility.compare(
            f.vps, vui, f.pps, f.vps, vui, f.pps
        )
        assertTrue(identicalVuiResult.reasons.toString(), identicalVuiResult.compatible)

        val changedVui = vui.copy(vuiMetadata = vuiMetadata.copy(timeScale = 50))
        val changedVuiResult = HevcParameterSetCompatibility.compare(
            f.vps, vui, f.pps, f.vps, changedVui, f.pps
        )
        assertFalse(changedVuiResult.compatible)
        assertTrue(changedVuiResult.reasons.any { it.contains("SPS metadata differs") })

        val unsupportedSpsExtension = vui.copy(spsExtensionPresent = true)
        val spsExtensionResult = HevcParameterSetCompatibility.compare(
            f.vps, unsupportedSpsExtension, f.pps, f.vps, unsupportedSpsExtension, f.pps
        )
        assertFalse(spsExtensionResult.compatible)
        assertTrue(spsExtensionResult.reasons.any { it.contains("SPS extension") })

        val extension = f.pps.copy(extensionPresent = true)
        val extensionResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, extension, f.vps, f.sps, extension
        )
        assertFalse(extensionResult.compatible)
        assertTrue(extensionResult.reasons.any { it.contains("PPS extensions") })

        val wavefront = f.pps.copy(entropyCodingSyncEnabled = true)
        val wavefrontResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, wavefront, f.vps, f.sps, wavefront
        )
        assertFalse(wavefrontResult.compatible)
        assertTrue(wavefrontResult.reasons.any { it.contains("Wavefront") })
    }

    @Test fun rejectsMissingOrNonTiledParameterSets() {
        val f = fixture()
        val missing = HevcParameterSetCompatibility.compare(
            null, f.sps, f.pps, f.vps, f.sps, f.pps
        )
        assertFalse(missing.compatible)
        assertTrue(missing.reasons.any { it.contains("VPS is missing") })

        val noTiles = f.pps.copy(tilesEnabled = false)
        val result = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, noTiles, f.vps, f.sps, noTiles
        )
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("must declare HEVC tiles") })
    }

    private data class Fixture(
        val vps: HevcParameterSets.VpsMetadata,
        val sps: HevcParameterSets.SpsMetadata,
        val pps: HevcParameterSets.PpsMetadata
    )

    private fun fixture(): Fixture {
        val vps = HevcParameterSets.parseVps(nal(32, BitWriter().apply {
            write(1, 4); write(1, 1); write(1, 1); write(0, 6)
            write(0, 3); write(1, 1); write(0xffff, 16)
            write(0, 8); write(0, 32); write(0, 32); write(0, 16); write(0, 8)
            write(1, 1) // ordering info present
            writeUe(4); writeUe(2); writeUe(0)
            write(0, 6); writeUe(0) // layer sets
            write(0, 1) // no timing info
            write(0, 1) // no VPS extension
        }.finish()))
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        return Fixture(vps, sps, pps)
    }

    private fun spsBits(): ByteArray = BitWriter().apply {
        write(1, 4); write(0, 3); write(1, 1)
        write(0, 8 + 32 + 4 + 44 + 8) // general PTL
        writeUe(0); writeUe(1) // SPS id, 4:2:0
        writeUe(1280); writeUe(720); write(0, 1) // dimensions, no crop
        writeUe(0); writeUe(0); writeUe(4) // bit depth, POC width
        write(1, 1); writeUe(4); writeUe(2); writeUe(0) // DPB ordering
        writeUe(0); writeUe(3) // 64x64 CTB
        writeUe(0); writeUe(0); writeUe(0); writeUe(0) // transform block limits
        write(0, 1) // scaling list disabled
        write(0, 1) // AMP disabled
        write(0, 1) // SAO disabled
        write(0, 1) // PCM disabled
        writeUe(0) // no short-term RPS
        write(0, 1) // no long-term RPS
        write(0, 1) // temporal MVP disabled
        write(1, 1) // strong intra smoothing
        write(0, 1) // no VUI
        write(0, 1) // no SPS extension
    }.finish()

    private fun ppsBits(): ByteArray = BitWriter().apply {
        writeUe(0); writeUe(0) // PPS and SPS ids
        write(0, 1); write(0, 1); write(0, 3) // dependent slices, output flag, extra bits
        write(0, 1); write(0, 1) // sign hiding and CABAC init
        writeUe(0); writeUe(0); writeSe(0) // default refs and init QP
        write(0, 1); write(0, 1); write(0, 1) // constrained intra, transform skip, CU QP delta
        writeSe(0); writeSe(0); write(0, 1) // chroma QP offsets, slice offsets flag
        write(0, 1); write(0, 1); write(0, 1) // weighted pred/bipred, transquant bypass
        write(1, 1); write(0, 1) // tiles enabled, entropy sync disabled
        writeUe(1); writeUe(0) // two columns, one row
        write(1, 1); write(1, 1) // uniform tile spacing, loop filter across tiles
        write(1, 1) // loop filter across slices
        write(0, 1) // no deblocking control
        write(0, 1) // no PPS scaling-list data
        write(0, 1) // no reference-list modification
        writeUe(0) // parallel merge level
        write(0, 1) // no slice header extension
        write(0, 1) // no PPS extension
    }.finish()

    private fun nal(type: Int, rbsp: ByteArray): ByteArray =
        byteArrayOf((type shl 1).toByte(), 0x01) + rbsp

    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += (value ushr i) and 1
        }
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
