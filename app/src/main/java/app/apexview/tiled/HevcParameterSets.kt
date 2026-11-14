package app.apexview.tiled

/**
 * Bounded prefix parser for HEVC VPS/SPS/PPS parameter sets.
 *
 * It extracts only fields needed to compare candidate tiled feeds: parameter-set IDs,
 * picture dimensions, POC LSB width, CTB dimensions and PPS tile geometry. It deliberately
 * stops before reference-picture-set/VUI parsing and never attempts to rewrite a slice header.
 * Input must be clear NAL bytes, including the two-byte HEVC NAL header.
 */
internal object HevcParameterSets {
    data class VpsMetadata(
        val vpsId: Int,
        val maxLayers: Int,
        val maxSubLayers: Int,
        val temporalIdNesting: Boolean
    )

    data class SpsMetadata(
        val vpsId: Int,
        val spsId: Int,
        val maxSubLayers: Int,
        val temporalIdNesting: Boolean,
        val chromaFormatIdc: Int,
        val separateColourPlane: Boolean,
        val width: Int,
        val height: Int,
        val log2MaxPicOrderCntLsb: Int,
        val log2CtbSize: Int,
        val ctbSize: Int,
        val picWidthInCtbs: Int,
        val picHeightInCtbs: Int
    )

    data class PpsMetadata(
        val ppsId: Int,
        val spsId: Int,
        val dependentSliceSegmentsEnabled: Boolean,
        val outputFlagPresent: Boolean,
        val extraSliceHeaderBits: Int,
        val tilesEnabled: Boolean,
        val entropyCodingSyncEnabled: Boolean,
        val uniformTileSpacing: Boolean?,
        val numTileColumns: Int,
        val numTileRows: Int,
        val explicitColumnWidthsMinus1: List<Int>,
        val explicitRowHeightsMinus1: List<Int>,
        val tileColumnWidthsInCtbs: List<Int>?,
        val tileRowHeightsInCtbs: List<Int>?,
        val loopFilterAcrossTiles: Boolean?
    )

    fun parseVps(nal: ByteArray): VpsMetadata {
        val bits = readerFor(nal, 32)
        val id = bits.readBits(4)
        bits.readBit() // vps_base_layer_internal_flag
        bits.readBit() // vps_base_layer_available_flag
        val maxLayers = bits.readBits(6) + 1
        val maxSubLayers = bits.readBits(3) + 1
        require(maxSubLayers in 1..7) { "Invalid VPS sub-layer count" }
        val nesting = bits.readBit() != 0
        require(bits.readBits(16) == 0xffff) { "Invalid VPS reserved bits" }
        return VpsMetadata(id, maxLayers, maxSubLayers, nesting)
    }

    fun parseSps(nal: ByteArray): SpsMetadata {
        val bits = readerFor(nal, 33)
        val vpsId = bits.readBits(4)
        val maxSubLayersMinus1 = bits.readBits(3)
        require(maxSubLayersMinus1 in 0..6) { "Invalid SPS sub-layer count" }
        val temporalNesting = bits.readBit() != 0
        skipProfileTierLevel(bits, maxSubLayersMinus1)

        val spsId = readUe(bits, 15, "sps_seq_parameter_set_id")
        val chromaFormat = readUe(bits, 3, "chroma_format_idc")
        val separateColourPlane = chromaFormat == 3 && bits.readBit() != 0
        val width = readUe(bits, 32768, "pic_width_in_luma_samples")
        val height = readUe(bits, 32768, "pic_height_in_luma_samples")
        require(width > 0 && height > 0) { "SPS picture dimensions must be non-zero" }
        if (bits.readBit() != 0) {
            repeat(4) { readUe(bits, 32768, "conformance_window_offset") }
        }
        readUe(bits, 8, "bit_depth_luma_minus8")
        readUe(bits, 8, "bit_depth_chroma_minus8")
        val pocMinus4 = readUe(bits, 12, "log2_max_pic_order_cnt_lsb_minus4")
        val log2Poc = pocMinus4 + 4

        val orderingInfoPresent = bits.readBit() != 0
        val firstLayer = if (orderingInfoPresent) 0 else maxSubLayersMinus1
        for (layer in firstLayer..maxSubLayersMinus1) {
            readUe(bits, 32, "sps_max_dec_pic_buffering_minus1")
            readUe(bits, 32, "sps_max_num_reorder_pics")
            readUe(bits, 1024, "sps_max_latency_increase_plus1")
        }

        val log2MinCbMinus3 = readUe(bits, 3, "log2_min_luma_coding_block_size_minus3")
        val log2DiffMaxMinCb = readUe(bits, 3, "log2_diff_max_min_luma_coding_block_size")
        val log2Ctb = log2MinCbMinus3 + 3 + log2DiffMaxMinCb
        require(log2Ctb in 3..6) { "Unsupported HEVC CTB size" }
        val ctbSize = 1 shl log2Ctb
        val widthInCtbs = (width + ctbSize - 1) / ctbSize
        val heightInCtbs = (height + ctbSize - 1) / ctbSize

        return SpsMetadata(vpsId, spsId, maxSubLayersMinus1 + 1, temporalNesting,
            chromaFormat, separateColourPlane, width, height, log2Poc, log2Ctb,
            ctbSize, widthInCtbs, heightInCtbs)
    }

    fun parsePps(nal: ByteArray, sps: SpsMetadata? = null): PpsMetadata {
        val bits = readerFor(nal, 34)
        val ppsId = readUe(bits, 63, "pps_pic_parameter_set_id")
        val spsId = readUe(bits, 15, "pps_seq_parameter_set_id")
        if (sps != null) require(sps.spsId == spsId) { "PPS references a different SPS" }

        val dependentSlices = bits.readBit() != 0
        val outputFlag = bits.readBit() != 0
        val extraHeaderBits = bits.readBits(3)
        bits.readBit() // sign_data_hiding_enabled_flag
        bits.readBit() // cabac_init_present_flag
        readUe(bits, 14, "num_ref_idx_l0_default_active_minus1")
        readUe(bits, 14, "num_ref_idx_l1_default_active_minus1")
        bits.readSignedExpGolomb() // init_qp_minus26
        bits.readBit() // constrained_intra_pred_flag
        bits.readBit() // transform_skip_enabled_flag
        if (bits.readBit() != 0) readUe(bits, 6, "diff_cu_qp_delta_depth")
        bits.readSignedExpGolomb() // pps_cb_qp_offset
        bits.readSignedExpGolomb() // pps_cr_qp_offset
        bits.readBit() // pps_slice_chroma_qp_offsets_present_flag
        bits.readBit() // weighted_pred_flag
        bits.readBit() // weighted_bipred_flag
        bits.readBit() // transquant_bypass_enabled_flag

        val tilesEnabled = bits.readBit() != 0
        val entropySync = bits.readBit() != 0
        if (!tilesEnabled) {
            val columns = sps?.let { listOf(it.picWidthInCtbs) }
            val rows = sps?.let { listOf(it.picHeightInCtbs) }
            return PpsMetadata(ppsId, spsId, dependentSlices, outputFlag, extraHeaderBits,
                false, entropySync, null, 1, 1, emptyList(), emptyList(), columns, rows, null)
        }

        val numColumns = readUe(bits, 1023, "num_tile_columns_minus1") + 1
        val numRows = readUe(bits, 1023, "num_tile_rows_minus1") + 1
        if (sps != null) {
            require(numColumns <= sps.picWidthInCtbs) { "Tile columns exceed picture CTB width" }
            require(numRows <= sps.picHeightInCtbs) { "Tile rows exceed picture CTB height" }
        }
        val uniform = bits.readBit() != 0
        val explicitColumns = mutableListOf<Int>()
        val explicitRows = mutableListOf<Int>()
        if (!uniform) {
            repeat(numColumns - 1) { explicitColumns += readUe(bits, 32767, "column_width_minus1") }
            repeat(numRows - 1) { explicitRows += readUe(bits, 32767, "row_height_minus1") }
        }
        val loopFilter = bits.readBit() != 0

        val columnWidths = sps?.let {
            if (uniform) uniformPartition(it.picWidthInCtbs, numColumns)
            else explicitPartition(it.picWidthInCtbs, explicitColumns, numColumns, "column")
        }
        val rowHeights = sps?.let {
            if (uniform) uniformPartition(it.picHeightInCtbs, numRows)
            else explicitPartition(it.picHeightInCtbs, explicitRows, numRows, "row")
        }
        return PpsMetadata(ppsId, spsId, dependentSlices, outputFlag, extraHeaderBits,
            true, entropySync, uniform, numColumns, numRows, explicitColumns, explicitRows,
            columnWidths, rowHeights, loopFilter)
    }

    private fun readerFor(nal: ByteArray, expectedType: Int): HevcBitReader {
        val header = HevcBitstream.parseNalHeader(nal)
        require(header.type == expectedType) { "Expected HEVC NAL type $expectedType, got ${header.type}" }
        require(header.layerId == 0) { "Non-zero HEVC layer IDs are not supported by this parser" }
        return HevcBitReader(HevcBitstream.ebspToRbsp(nal.copyOfRange(2, nal.size)))
    }

    private fun skipProfileTierLevel(bits: HevcBitReader, maxSubLayersMinus1: Int) {
        // general_profile_* and general_level_idc
        bits.skipBits(8 + 32 + 4 + 44 + 8)
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = bits.readBit() != 0
            levelPresent[i] = bits.readBit() != 0
        }
        if (maxSubLayersMinus1 > 0) {
            for (i in maxSubLayersMinus1 until 8) bits.skipBits(2)
        }
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) bits.skipBits(8 + 32 + 4 + 44)
            if (levelPresent[i]) bits.skipBits(8)
        }
    }

    private fun readUe(bits: HevcBitReader, max: Int, field: String): Int {
        val value = bits.readUnsignedExpGolomb()
        require(value <= max.toLong()) { "$field exceeds supported range" }
        return value.toInt()
    }

    private fun uniformPartition(total: Int, parts: Int): List<Int> =
        (0 until parts).map { ((it + 1) * total / parts) - (it * total / parts) }

    private fun explicitPartition(total: Int, leadingMinus1: List<Int>, parts: Int, label: String): List<Int> {
        require(leadingMinus1.size == parts - 1) { "Incomplete explicit tile $label geometry" }
        val leading = leadingMinus1.map { it + 1 }
        val remaining = total - leading.sum()
        require(remaining > 0) { "Explicit tile $label geometry exceeds picture" }
        return leading + remaining
    }
}

private fun HevcBitReader.skipBits(count: Int) {
    require(count >= 0) { "Cannot skip a negative number of bits" }
    var remaining = count
    while (remaining > 0) {
        val chunk = minOf(remaining, 32)
        readBits(chunk)
        remaining -= chunk
    }
}

private fun HevcBitReader.readSignedExpGolomb(): Long {
    val codeNum = readUnsignedExpGolomb()
    return if ((codeNum and 1L) != 0L) (codeNum + 1L) / 2L else -(codeNum / 2L)
}
