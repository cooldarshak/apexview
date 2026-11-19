package app.apexview.tiled

import java.io.ByteArrayOutputStream

/**
 * Builds a new output VPS/SPS/PPS set from a clear source parameter-set template.
 *
 * The writer performs syntax-aware splicing rather than serialising fields that the bounded
 * parser does not model. Existing parsed syntax is retained bit-for-bit; only parameter-set
 * identifiers, output SPS dimensions/level, and output PPS tile declarations are replaced.
 * Unparsed extensions and WPP are rejected. This class accepts clear parameter-set NALs only.
 */
internal object HevcOutputParameterSetWriter {
    data class Request(
        val vpsId: Int,
        val spsId: Int,
        val ppsId: Int,
        val codedWidth: Int,
        val codedHeight: Int,
        val tileColumnWidthsInCtbs: List<Int>,
        val tileRowHeightsInCtbs: List<Int>,
        val loopFilterAcrossTilesEnabled: Boolean,
        val frameRateNumerator: Int,
        val frameRateDenominator: Int = 1,
        /** A mixed-size atlas uses per-source crops, not the template feed's global crop. */
        val uncroppedOutputPicture: Boolean = false
    )

    data class Output(
        val vps: ByteArray,
        val sps: ByteArray,
        val pps: ByteArray,
        val levelIdc: Int,
        val codedWidth: Int,
        val codedHeight: Int,
        val tileColumnWidthsInCtbs: List<Int>,
        val tileRowHeightsInCtbs: List<Int>
    )

    private data class Patch(val start: Int, val end: Int, val replacement: List<Int>)
    private data class LevelLimit(
        val levelIdc: Int,
        val maxLumaPictureSamples: Long,
        val maxLumaSamplesPerSecond: Long,
        val maxSliceSegmentsPerPicture: Int,
        val maxTileRows: Int,
        val maxTileColumns: Int
    )

    private val levelLimits = listOf(
        LevelLimit(30, 36_864, 552_960, 16, 1, 1),
        LevelLimit(60, 122_880, 3_686_400, 16, 1, 1),
        LevelLimit(63, 245_760, 7_372_800, 20, 1, 1),
        LevelLimit(90, 552_960, 16_588_800, 30, 2, 2),
        LevelLimit(93, 983_040, 33_177_600, 40, 3, 3),
        LevelLimit(120, 2_228_224, 66_846_720, 75, 5, 5),
        LevelLimit(123, 2_228_224, 133_693_440, 75, 5, 5),
        LevelLimit(150, 8_912_896, 267_386_880, 200, 11, 10),
        LevelLimit(153, 8_912_896, 534_773_760, 200, 11, 10),
        LevelLimit(156, 8_912_896, 1_069_547_520, 200, 11, 10),
        LevelLimit(180, 35_651_584, 1_069_547_520, 600, 22, 20),
        LevelLimit(183, 35_651_584, 2_139_095_040, 600, 22, 20),
        LevelLimit(186, 35_651_584, 4_278_190_080, 600, 22, 20)
    )

    fun generate(
        sourceVpsNal: ByteArray,
        sourceSpsNal: ByteArray,
        sourcePpsNal: ByteArray,
        request: Request
    ): Output {
        require(request.vpsId in 0..15) { "Output VPS id must be in 0..15" }
        require(request.spsId in 0..15) { "Output SPS id must be in 0..15" }
        require(request.ppsId in 0..63) { "Output PPS id must be in 0..63" }
        require(request.codedWidth in 1..32_768 && request.codedHeight in 1..32_768) {
            "Output dimensions exceed supported HEVC bounds"
        }
        require(request.frameRateNumerator > 0 && request.frameRateDenominator > 0) {
            "Output frame rate must be positive"
        }
        require(request.tileColumnWidthsInCtbs.isNotEmpty() && request.tileRowHeightsInCtbs.isNotEmpty()) {
            "Output tile grid must contain at least one row and column"
        }
        require(request.tileColumnWidthsInCtbs.size <= 1024 && request.tileRowHeightsInCtbs.size <= 1024) {
            "Output tile grid exceeds HEVC tile-count bounds"
        }
        require(request.tileColumnWidthsInCtbs.all { it > 0 } && request.tileRowHeightsInCtbs.all { it > 0 }) {
            "Output tile dimensions must be positive"
        }

        val sourceVps = HevcParameterSets.parseVps(sourceVpsNal)
        val sourceSps = HevcParameterSets.parseSps(sourceSpsNal)
        val sourcePps = HevcParameterSets.parsePps(sourcePpsNal, sourceSps)
        require(sourceSps.vpsId == sourceVps.vpsId) { "Source SPS does not reference the supplied VPS" }
        require(sourcePps.spsId == sourceSps.spsId) { "Source PPS does not reference the supplied SPS" }
        require(sourceVps.maxLayers == 1) { "Multilayer VPS templates are not supported" }
        require(!sourceVps.extensionPresent && !sourceSps.spsExtensionPresent && !sourcePps.extensionPresent) {
            "Parameter-set extensions are not supported by the output parameter-set writer"
        }
        require(!sourcePps.entropyCodingSyncEnabled) { "WPP output PPS is not supported" }

        val ctbSize = sourceSps.ctbSize
        require(ctbSize in 8..64 && sourceSps.log2CtbSize in 3..6) {
            "Source CTB geometry is not supported"
        }
        val pictureWidthInCtbs = ceilDiv(request.codedWidth, ctbSize)
        val pictureHeightInCtbs = ceilDiv(request.codedHeight, ctbSize)
        require(request.tileColumnWidthsInCtbs.sumOf { it.toLong() } == pictureWidthInCtbs.toLong()) {
            "Output tile columns do not cover the output picture CTB width"
        }
        require(request.tileRowHeightsInCtbs.sumOf { it.toLong() } == pictureHeightInCtbs.toLong()) {
            "Output tile rows do not cover the output picture CTB height"
        }

        val requiredLevel = requiredLevel(
            request.codedWidth.toLong() * request.codedHeight.toLong(),
            request.frameRateNumerator,
            request.frameRateDenominator,
            request.tileColumnWidthsInCtbs.size,
            request.tileRowHeightsInCtbs.size,
            Math.multiplyExact(request.tileColumnWidthsInCtbs.size, request.tileRowHeightsInCtbs.size)
        )
        val outputLevel = maxOf(
            requiredLevel,
            sourceVps.profileTierLevel.levelIdc,
            sourceSps.profileTierLevel.levelIdc
        )
        require(levelLimits.any { it.levelIdc == outputLevel }) {
            "Output level_idc $outputLevel is not a supported HEVC level"
        }

        val outputVps = rewriteVps(sourceVpsNal, request.vpsId, outputLevel)
        val outputSps = rewriteSps(
            sourceSpsNal, request.vpsId, request.spsId,
            request.codedWidth, request.codedHeight, outputLevel, request.uncroppedOutputPicture
        )
        val outputPps = rewritePps(
            sourcePpsNal, request.ppsId, request.spsId,
            request.tileColumnWidthsInCtbs, request.tileRowHeightsInCtbs,
            request.loopFilterAcrossTilesEnabled
        )

        val parsedVps = HevcParameterSets.parseVps(outputVps)
        val parsedSps = HevcParameterSets.parseSps(outputSps)
        val parsedPps = HevcParameterSets.parsePps(outputPps, parsedSps)
        require(parsedVps.vpsId == request.vpsId) { "Generated VPS id failed round-trip validation" }
        require(parsedSps.vpsId == request.vpsId && parsedSps.spsId == request.spsId) {
            "Generated SPS references failed round-trip validation"
        }
        require(parsedSps.width == request.codedWidth && parsedSps.height == request.codedHeight) {
            "Generated SPS dimensions failed round-trip validation"
        }
        if (request.uncroppedOutputPicture) require(
            parsedSps.displayWidth == request.codedWidth && parsedSps.displayHeight == request.codedHeight
        ) { "Mixed-size atlas SPS must expose the complete coded picture" }
        require(parsedPps.ppsId == request.ppsId && parsedPps.spsId == request.spsId) {
            "Generated PPS references failed round-trip validation"
        }
        require(parsedPps.tilesEnabled && !parsedPps.entropyCodingSyncEnabled) {
            "Generated PPS does not declare the requested independent tile grid"
        }
        require(parsedPps.tileColumnWidthsInCtbs == request.tileColumnWidthsInCtbs &&
            parsedPps.tileRowHeightsInCtbs == request.tileRowHeightsInCtbs) {
            "Generated PPS tile geometry failed round-trip validation"
        }
        require(parsedPps.loopFilterAcrossTiles == request.loopFilterAcrossTilesEnabled) {
            "Generated PPS loop-filter policy failed round-trip validation"
        }
        require(parsedSps.profileTierLevel.levelIdc == outputLevel &&
            parsedVps.profileTierLevel.levelIdc == outputLevel) {
            "Generated VPS/SPS level signalling is inconsistent"
        }

        return Output(
            outputVps, outputSps, outputPps, outputLevel,
            parsedSps.width, parsedSps.height,
            requireNotNull(parsedPps.tileColumnWidthsInCtbs),
            requireNotNull(parsedPps.tileRowHeightsInCtbs)
        )
    }

    private fun rewriteVps(nal: ByteArray, vpsId: Int, levelIdc: Int): ByteArray {
        require(HevcBitstream.parseNalHeader(nal).type == 32) { "Expected a VPS NAL" }
        val bits = parameterSyntaxBits(nal)
        val reader = BitCursor(bits)
        val idStart = reader.position
        reader.skip(4)
        val idEnd = reader.position
        reader.skip(1 + 1 + 6)
        val maxSubLayersMinus1 = reader.readBits(3)
        reader.skip(1 + 16)
        val profileTierLevelStart = reader.position
        skipProfileTierLevel(reader, maxSubLayersMinus1)
        val levelStart = profileTierLevelStart + 88
        require(levelStart + 8 <= bits.size) { "VPS profile-tier-level is truncated" }
        return spliceNal(nal, bits, listOf(
            Patch(idStart, idEnd, fixedBits(vpsId, 4)),
            Patch(levelStart, levelStart + 8, fixedBits(levelIdc, 8))
        ))
    }

    private fun rewriteSps(
        nal: ByteArray,
        vpsId: Int,
        spsId: Int,
        width: Int,
        height: Int,
        levelIdc: Int,
        uncroppedOutputPicture: Boolean
    ): ByteArray {
        require(HevcBitstream.parseNalHeader(nal).type == 33) { "Expected an SPS NAL" }
        val bits = parameterSyntaxBits(nal)
        val reader = BitCursor(bits)
        val vpsStart = reader.position
        reader.skip(4)
        val vpsEnd = reader.position
        val maxSubLayersMinus1 = reader.readBits(3)
        reader.skip(1)
        val profileTierLevelStart = reader.position
        skipProfileTierLevel(reader, maxSubLayersMinus1)
        val levelStart = profileTierLevelStart + 88

        val spsIdStart = reader.position
        reader.readUe()
        val spsIdEnd = reader.position
        val chromaFormat = reader.readUe()
        if (chromaFormat == 3) reader.skip(1)

        val widthStart = reader.position
        reader.readUe()
        val widthEnd = reader.position
        val heightStart = reader.position
        reader.readUe()
        val heightEnd = reader.position

        // SPS conformance_window_flag and the four optional offsets immediately follow
        // pic_height_in_luma_samples. A source feed's bottom crop applies only to its own
        // picture. Retaining it on a mixed-resolution atlas can crop a different feed.
        val conformanceStart = reader.position
        if (reader.readBit() != 0) repeat(4) { reader.readUe() }
        val conformanceEnd = reader.position

        require(levelStart + 8 <= bits.size) { "SPS profile-tier-level is truncated" }
        return spliceNal(nal, bits, listOfNotNull(
            Patch(vpsStart, vpsEnd, fixedBits(vpsId, 4)),
            Patch(spsIdStart, spsIdEnd, unsignedExpGolombBits(spsId.toLong())),
            Patch(widthStart, widthEnd, unsignedExpGolombBits(width.toLong())),
            Patch(heightStart, heightEnd, unsignedExpGolombBits(height.toLong())),
            if (uncroppedOutputPicture) Patch(conformanceStart, conformanceEnd, listOf(0)) else null,
            Patch(levelStart, levelStart + 8, fixedBits(levelIdc, 8))
        ))
    }

    private fun rewritePps(
        nal: ByteArray,
        ppsId: Int,
        spsId: Int,
        tileColumns: List<Int>,
        tileRows: List<Int>,
        loopFilterAcrossTiles: Boolean
    ): ByteArray {
        require(HevcBitstream.parseNalHeader(nal).type == 34) { "Expected a PPS NAL" }
        val bits = parameterSyntaxBits(nal)
        val reader = BitCursor(bits)
        val ppsIdStart = reader.position
        reader.readUe()
        val ppsIdEnd = reader.position
        val spsIdStart = reader.position
        reader.readUe()
        val spsIdEnd = reader.position

        reader.skip(1 + 1 + 3 + 1 + 1)
        reader.readUe() // num_ref_idx_l0_default_active_minus1
        reader.readUe() // num_ref_idx_l1_default_active_minus1
        reader.readSe() // init_qp_minus26
        reader.skip(1 + 1)
        if (reader.readBit() != 0) reader.readUe() // cu_qp_delta_enabled_flag
        reader.readSe() // pps_cb_qp_offset
        reader.readSe() // pps_cr_qp_offset
        reader.skip(1 + 1 + 1 + 1) // chroma offsets, weighted prediction, biprediction, bypass

        val tileStart = reader.position
        val sourceTilesEnabled = reader.readBit() != 0
        val entropyCodingSyncEnabled = reader.readBit() != 0
        require(!entropyCodingSyncEnabled) { "WPP source PPS cannot be converted by this writer" }
        if (sourceTilesEnabled) {
            val sourceColumns = reader.readUe() + 1
            val sourceRows = reader.readUe() + 1
            val uniform = reader.readBit() != 0
            if (!uniform) {
                repeat(sourceColumns - 1) { reader.readUe() }
                repeat(sourceRows - 1) { reader.readUe() }
            }
            reader.skip(1) // loop_filter_across_tiles_enabled_flag
        }
        val tileEnd = reader.position

        val replacement = BitWriter()
        replacement.writeBit(1) // tiles_enabled_flag
        replacement.writeBit(0) // entropy_coding_sync_enabled_flag (WPP)
        replacement.writeUe((tileColumns.size - 1).toLong())
        replacement.writeUe((tileRows.size - 1).toLong())
        val uniform = isUniformPartition(tileColumns) && isUniformPartition(tileRows)
        replacement.writeBit(if (uniform) 1 else 0)
        if (!uniform) {
            tileColumns.dropLast(1).forEach { replacement.writeUe((it - 1).toLong()) }
            tileRows.dropLast(1).forEach { replacement.writeUe((it - 1).toLong()) }
        }
        replacement.writeBit(if (loopFilterAcrossTiles) 1 else 0)

        return spliceNal(nal, bits, listOf(
            Patch(ppsIdStart, ppsIdEnd, unsignedExpGolombBits(ppsId.toLong())),
            Patch(spsIdStart, spsIdEnd, unsignedExpGolombBits(spsId.toLong())),
            Patch(tileStart, tileEnd, replacement.bits)
        ))
    }

    private fun isUniformPartition(lengths: List<Int>): Boolean {
        if (lengths.isEmpty()) return false
        val total = lengths.sumOf { it.toLong() }
        return lengths.indices.all { index ->
            val end = ((index + 1L) * total) / lengths.size
            val start = (index.toLong() * total) / lengths.size
            lengths[index].toLong() == end - start
        }
    }

    internal fun requiredLevel(
        pictureSamples: Long,
        fpsNumerator: Int,
        fpsDenominator: Int,
        tileColumns: Int,
        tileRows: Int,
        sliceSegments: Int
    ): Int {
        require(pictureSamples > 0 && fpsNumerator > 0 && fpsDenominator > 0 &&
            tileColumns > 0 && tileRows > 0 && sliceSegments > 0) {
            "Output picture, rate, and tile geometry must be positive"
        }
        val rateNumerator = Math.multiplyExact(pictureSamples, fpsNumerator.toLong())
        return levelLimits.firstOrNull { limit ->
            pictureSamples <= limit.maxLumaPictureSamples &&
                rateNumerator <= limit.maxLumaSamplesPerSecond * fpsDenominator.toLong() &&
                tileColumns <= limit.maxTileColumns && tileRows <= limit.maxTileRows &&
                sliceSegments <= limit.maxSliceSegmentsPerPicture
        }?.levelIdc ?: throw IllegalArgumentException(
            "Output picture, frame rate, tile grid, or slice count exceeds HEVC level 6.2 limits"
        )
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private fun skipProfileTierLevel(reader: BitCursor, maxSubLayersMinus1: Int) {
        reader.skip(96) // general profile_tier_level and general_level_idc
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        repeat(maxSubLayersMinus1) { index ->
            profilePresent[index] = reader.readBit() != 0
            levelPresent[index] = reader.readBit() != 0
        }
        if (maxSubLayersMinus1 > 0) repeat(8 - maxSubLayersMinus1) { reader.skip(2) }
        repeat(maxSubLayersMinus1) { index ->
            if (profilePresent[index]) reader.skip(88)
            if (levelPresent[index]) reader.skip(8)
        }
    }

    private fun parameterSyntaxBits(nal: ByteArray): List<Int> {
        require(nal.size > 2) { "Truncated parameter-set NAL" }
        val rbsp = HevcBitstream.ebspToRbsp(nal.copyOfRange(2, nal.size))
        val all = bytesToBits(rbsp)
        val stop = all.indexOfLast { it == 1 }
        require(stop >= 0) { "Parameter set is missing rbsp_stop_one_bit" }
        return all.subList(0, stop)
    }

    private fun spliceNal(nal: ByteArray, source: List<Int>, patches: List<Patch>): ByteArray {
        val writer = BitWriter()
        var cursor = 0
        patches.sortedBy { it.start }.forEach { patch ->
            require(patch.start >= cursor && patch.end >= patch.start && patch.end <= source.size) {
                "Overlapping or out-of-range parameter-set syntax patch"
            }
            writer.append(source, cursor, patch.start)
            writer.append(patch.replacement)
            cursor = patch.end
        }
        writer.append(source, cursor, source.size)
        return nal.copyOfRange(0, 2) + escapeRbsp(writer.finishRbsp())
    }

    private fun fixedBits(value: Int, width: Int): List<Int> {
        require(width in 1..32 && value >= 0) { "Invalid fixed-width bit field" }
        return (width - 1 downTo 0).map { (value ushr it) and 1 }
    }

    private fun unsignedExpGolombBits(value: Long): List<Int> {
        require(value >= 0) { "Unsigned Exp-Golomb value cannot be negative" }
        val codeNum = value + 1L
        require(codeNum > 0) { "Unsigned Exp-Golomb value overflow" }
        val width = 64 - java.lang.Long.numberOfLeadingZeros(codeNum)
        require(width <= 32) { "Unsigned Exp-Golomb value exceeds supported range" }
        return List(width - 1) { 0 } + (width - 1 downTo 0).map { ((codeNum ushr it) and 1L).toInt() }
    }

    private fun bytesToBits(bytes: ByteArray): List<Int> = buildList(bytes.size * 8) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            for (bit in 7 downTo 0) add((value ushr bit) and 1)
        }
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

    private class BitCursor(private val bits: List<Int>) {
        var position: Int = 0

        fun readBit(): Int = readBits(1)

        fun readBits(count: Int): Int {
            require(count in 0..32 && position + count <= bits.size) {
                "Parameter-set syntax is truncated"
            }
            var value = 0
            repeat(count) { value = (value shl 1) or bits[position++] }
            return value
        }

        fun skip(count: Int) {
            require(count >= 0 && position + count <= bits.size) { "Parameter-set syntax is truncated" }
            position += count
        }

        fun readUe(): Int {
            var leadingZeros = 0
            while (true) {
                require(position < bits.size) { "Unterminated Exp-Golomb value" }
                if (readBit() == 1) break
                leadingZeros++
                require(leadingZeros <= 30) { "Exp-Golomb value exceeds supported range" }
            }
            val suffix = readBits(leadingZeros)
            return ((1L shl leadingZeros) - 1L + suffix.toLong()).also {
                require(it <= Int.MAX_VALUE) { "Exp-Golomb value exceeds Int range" }
            }.toInt()
        }

        fun readSe(): Int {
            val codeNum = readUe().toLong()
            val value = if ((codeNum and 1L) != 0L) (codeNum + 1L) / 2L else -(codeNum / 2L)
            require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "Signed Exp-Golomb value exceeds Int range"
            }
            return value.toInt()
        }
    }

    private class BitWriter {
        val bits = mutableListOf<Int>()

        fun writeBit(value: Int) {
            require(value == 0 || value == 1)
            bits += value
        }

        fun writeUe(value: Long) {
            bits += unsignedExpGolombBits(value)
        }

        fun append(source: List<Int>, start: Int, end: Int) {
            require(start in 0..source.size && end in start..source.size)
            for (index in start until end) bits += source[index]
        }

        fun append(source: List<Int>) {
            bits += source
        }

        fun finishRbsp(): ByteArray {
            bits += 1 // rbsp_stop_one_bit
            while (bits.size % 8 != 0) bits += 0
            val output = ByteArray(bits.size / 8)
            bits.forEachIndexed { index, bit ->
                if (bit != 0) {
                    val byteIndex = index / 8
                    output[byteIndex] = (output[byteIndex].toInt() or (1 shl (7 - index % 8))).toByte()
                }
            }
            return output
        }
    }
}
