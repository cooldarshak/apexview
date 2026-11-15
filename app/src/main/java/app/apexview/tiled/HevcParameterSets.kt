package app.apexview.tiled

/**
 * Bounded prefix parser for HEVC VPS/SPS/PPS parameter sets.
 *
 * It extracts bounded decoder and tile metadata from clear VPS/SPS/PPS NALs. VUI payloads
 * and parameter-set extensions are identified but not interpreted; callers must fail closed
 * when those unparsed features are present. This parser never rewrites a slice header.
 * Input must be clear NAL bytes, including the two-byte HEVC NAL header.
 */
internal object HevcParameterSets {
    data class VpsMetadata(
        val vpsId: Int,
        val maxLayers: Int,
        val maxSubLayers: Int,
        val temporalIdNesting: Boolean
    )

    data class ProfileTierLevelFields(
        val profileSpace: Int,
        val tierFlag: Boolean,
        val profileIdc: Int,
        val compatibilityFlags: Long,
        val constraintIndicatorFlags: Long
    )

    data class ProfileTierLevel(
        val profileSpace: Int,
        val tierFlag: Boolean,
        val profileIdc: Int,
        val compatibilityFlags: Long,
        val constraintIndicatorFlags: Long,
        val levelIdc: Int,
        val subLayerProfilePresent: List<Boolean>,
        val subLayerLevelPresent: List<Boolean>,
        val subLayerProfiles: List<ProfileTierLevelFields?>,
        val subLayerLevelIdcs: List<Int?>
    )

    data class ReferenceDelta(val deltaPoc: Int, val usedByCurrent: Boolean)

    data class LongTermReference(val pocLsb: Int, val usedByCurrent: Boolean)

    data class ShortTermRefPicSet(
        val negative: List<ReferenceDelta>,
        val positive: List<ReferenceDelta>
    ) {
        val all: List<ReferenceDelta> get() = negative + positive
    }

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
        val picHeightInCtbs: Int,
        val sampleAdaptiveOffsetEnabled: Boolean,
        val temporalMvpEnabled: Boolean,
        val longTermRefPicsPresent: Boolean,
        val longTermRefPics: List<LongTermReference>,
        val shortTermRefPicSets: List<ShortTermRefPicSet>,
        val profileTierLevel: ProfileTierLevel,
        val bitDepthLuma: Int,
        val bitDepthChroma: Int,
        val conformanceWindowLeft: Int,
        val conformanceWindowRight: Int,
        val conformanceWindowTop: Int,
        val conformanceWindowBottom: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val maxDecPicBuffering: List<Int>,
        val maxNumReorderPics: List<Int>,
        val maxLatencyIncrease: List<Int>,
        val scalingListEnabled: Boolean,
        val scalingListDataPresent: Boolean,
        val ampEnabled: Boolean,
        val pcmEnabled: Boolean,
        val strongIntraSmoothingEnabled: Boolean,
        val vuiParametersPresent: Boolean,
        val spsExtensionPresent: Boolean?
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
        val loopFilterAcrossTiles: Boolean?,
        val weightedPredictionEnabled: Boolean,
        val weightedBipredictionEnabled: Boolean,
        val transquantBypassEnabled: Boolean,
        val loopFilterAcrossSlicesEnabled: Boolean,
        val deblockingFilterControlPresent: Boolean,
        val deblockingFilterOverrideEnabled: Boolean?,
        val deblockingFilterDisabled: Boolean?,
        val betaOffsetDiv2: Int?,
        val tcOffsetDiv2: Int?,
        val scalingListDataPresent: Boolean,
        val listsModificationPresent: Boolean,
        val log2ParallelMergeLevelMinus2: Int,
        val sliceSegmentHeaderExtensionPresent: Boolean,
        val extensionPresent: Boolean,
        val extensionFlags: Int?
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
        val profileTierLevel = parseProfileTierLevel(bits, maxSubLayersMinus1)

        val spsId = readUe(bits, 15, "sps_seq_parameter_set_id")
        val chromaFormat = readUe(bits, 3, "chroma_format_idc")
        val separateColourPlane = chromaFormat == 3 && bits.readBit() != 0
        val width = readUe(bits, 32768, "pic_width_in_luma_samples")
        val height = readUe(bits, 32768, "pic_height_in_luma_samples")
        require(width > 0 && height > 0) { "SPS picture dimensions must be non-zero" }
        val hasConformanceWindow = bits.readBit() != 0
        val conformanceLeft = if (hasConformanceWindow) readUe(bits, 32768, "conf_win_left_offset") else 0
        val conformanceRight = if (hasConformanceWindow) readUe(bits, 32768, "conf_win_right_offset") else 0
        val conformanceTop = if (hasConformanceWindow) readUe(bits, 32768, "conf_win_top_offset") else 0
        val conformanceBottom = if (hasConformanceWindow) readUe(bits, 32768, "conf_win_bottom_offset") else 0
        val cropUnitWidth = if (separateColourPlane) 1 else if (chromaFormat == 1 || chromaFormat == 2) 2 else 1
        val cropUnitHeight = if (separateColourPlane) 1 else if (chromaFormat == 1) 2 else 1
        val displayWidth = width - cropUnitWidth * (conformanceLeft + conformanceRight)
        val displayHeight = height - cropUnitHeight * (conformanceTop + conformanceBottom)
        require(displayWidth > 0 && displayHeight > 0) { "SPS conformance window removes the full picture" }
        val bitDepthLuma = readUe(bits, 8, "bit_depth_luma_minus8") + 8
        val bitDepthChroma = readUe(bits, 8, "bit_depth_chroma_minus8") + 8
        val pocMinus4 = readUe(bits, 12, "log2_max_pic_order_cnt_lsb_minus4")
        val log2Poc = pocMinus4 + 4

        val orderingInfoPresent = bits.readBit() != 0
        val maxDecPicBuffering = MutableList(maxSubLayersMinus1 + 1) { 0 }
        val maxNumReorderPics = MutableList(maxSubLayersMinus1 + 1) { 0 }
        val maxLatencyIncrease = MutableList(maxSubLayersMinus1 + 1) { 0 }
        val firstLayer = if (orderingInfoPresent) 0 else maxSubLayersMinus1
        for (layer in firstLayer..maxSubLayersMinus1) {
            maxDecPicBuffering[layer] = readUe(bits, 32, "sps_max_dec_pic_buffering_minus1") + 1
            maxNumReorderPics[layer] = readUe(bits, 32, "sps_max_num_reorder_pics")
            maxLatencyIncrease[layer] = readUe(bits, 1024, "sps_max_latency_increase_plus1")
        }
        if (!orderingInfoPresent) {
            for (layer in 0 until maxSubLayersMinus1) {
                maxDecPicBuffering[layer] = maxDecPicBuffering[maxSubLayersMinus1]
                maxNumReorderPics[layer] = maxNumReorderPics[maxSubLayersMinus1]
                maxLatencyIncrease[layer] = maxLatencyIncrease[maxSubLayersMinus1]
            }
        }

        val log2MinCbMinus3 = readUe(bits, 3, "log2_min_luma_coding_block_size_minus3")
        val log2DiffMaxMinCb = readUe(bits, 3, "log2_diff_max_min_luma_coding_block_size")
        val log2Ctb = log2MinCbMinus3 + 3 + log2DiffMaxMinCb
        require(log2Ctb in 3..6) { "Unsupported HEVC CTB size" }
        val ctbSize = 1 shl log2Ctb
        val widthInCtbs = (width + ctbSize - 1) / ctbSize
        val heightInCtbs = (height + ctbSize - 1) / ctbSize

        // Continue through the sequence fields needed to interpret slice headers.
        readUe(bits, 3, "log2_min_luma_transform_block_size_minus2")
        readUe(bits, 3, "log2_diff_max_min_luma_transform_block_size")
        readUe(bits, 4, "max_transform_hierarchy_depth_inter")
        readUe(bits, 4, "max_transform_hierarchy_depth_intra")
        val scalingListEnabled = bits.readBit() != 0
        val scalingListDataPresent = scalingListEnabled && bits.readBit() != 0
        if (scalingListDataPresent) skipScalingListData(bits)
        val ampEnabled = bits.readBit() != 0
        val saoEnabled = bits.readBit() != 0
        val pcmEnabled = bits.readBit() != 0
        if (pcmEnabled) {
            bits.skipBits(8) // PCM sample bit depths
            readUe(bits, 3, "log2_min_pcm_luma_coding_block_size_minus3")
            readUe(bits, 3, "log2_diff_max_min_pcm_luma_coding_block_size")
            bits.readBit() // pcm_loop_filter_disabled_flag
        }

        val numShortTermSets = readUe(bits, 64, "num_short_term_ref_pic_sets")
        val shortTermSets = mutableListOf<ShortTermRefPicSet>()
        repeat(numShortTermSets) { index ->
            shortTermSets += parseShortTermRefPicSet(bits, index, numShortTermSets, shortTermSets)
        }

        val longTermPresent = bits.readBit() != 0
        val longTermRefs = mutableListOf<LongTermReference>()
        if (longTermPresent) {
            val count = readUe(bits, 32, "num_long_term_ref_pics_sps")
            repeat(count) {
                longTermRefs += LongTermReference(bits.readBits(log2Poc), bits.readBit() != 0)
            }
        }
        val temporalMvp = bits.readBit() != 0
        val strongIntraSmoothing = bits.readBit() != 0
        val vuiPresent = bits.readBit() != 0
        // VUI is deliberately not parsed yet. Its presence is exposed so a stitcher can
        // reject streams whose colour/timing/display semantics are not fully compared.
        val spsExtensionPresent = if (vuiPresent || bits.bitsRemaining == 0) null else bits.readBit() != 0

        return SpsMetadata(
            vpsId, spsId, maxSubLayersMinus1 + 1, temporalNesting,
            chromaFormat, separateColourPlane, width, height, log2Poc, log2Ctb,
            ctbSize, widthInCtbs, heightInCtbs, saoEnabled, temporalMvp,
            longTermPresent, longTermRefs, shortTermSets,
            profileTierLevel, bitDepthLuma, bitDepthChroma,
            conformanceLeft, conformanceRight, conformanceTop, conformanceBottom,
            displayWidth, displayHeight, maxDecPicBuffering, maxNumReorderPics,
            maxLatencyIncrease, scalingListEnabled, scalingListDataPresent,
            ampEnabled, pcmEnabled, strongIntraSmoothing, vuiPresent, spsExtensionPresent
        )
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
        val weightedPred = bits.readBit() != 0
        val weightedBipred = bits.readBit() != 0
        val transquantBypass = bits.readBit() != 0

        val tilesEnabled = bits.readBit() != 0
        val entropySync = bits.readBit() != 0

        val numColumns = if (tilesEnabled) readUe(bits, 1023, "num_tile_columns_minus1") + 1 else 1
        val numRows = if (tilesEnabled) readUe(bits, 1023, "num_tile_rows_minus1") + 1 else 1
        if (sps != null) {
            require(numColumns <= sps.picWidthInCtbs) { "Tile columns exceed picture CTB width" }
            require(numRows <= sps.picHeightInCtbs) { "Tile rows exceed picture CTB height" }
        }
        val uniform = if (tilesEnabled) bits.readBit() != 0 else null
        val explicitColumns = mutableListOf<Int>()
        val explicitRows = mutableListOf<Int>()
        if (tilesEnabled && uniform == false) {
            repeat(numColumns - 1) { explicitColumns += readUe(bits, 32767, "column_width_minus1") }
            repeat(numRows - 1) { explicitRows += readUe(bits, 32767, "row_height_minus1") }
        }
        val loopFilter = if (tilesEnabled) bits.readBit() != 0 else null
        val columnWidths = sps?.let {
            if (!tilesEnabled) listOf(it.picWidthInCtbs)
            else if (uniform == true) uniformPartition(it.picWidthInCtbs, numColumns)
            else explicitPartition(it.picWidthInCtbs, explicitColumns, numColumns, "column")
        }
        val rowHeights = sps?.let {
            if (!tilesEnabled) listOf(it.picHeightInCtbs)
            else if (uniform == true) uniformPartition(it.picHeightInCtbs, numRows)
            else explicitPartition(it.picHeightInCtbs, explicitRows, numRows, "row")
        }

        val loopFilterAcrossSlices = bits.readBit() != 0
        val deblockingControlPresent = bits.readBit() != 0
        var deblockingOverride: Boolean? = null
        var deblockingDisabled: Boolean? = null
        var betaOffset: Int? = null
        var tcOffset: Int? = null
        if (deblockingControlPresent) {
            deblockingOverride = bits.readBit() != 0
            deblockingDisabled = bits.readBit() != 0
            if (deblockingDisabled == false) {
                val parsedBetaOffset = bits.readSignedExpGolomb().toInt()
                val parsedTcOffset = bits.readSignedExpGolomb().toInt()
                require(parsedBetaOffset in -6..6 && parsedTcOffset in -6..6) {
                    "PPS deblocking offsets exceed HEVC range"
                }
                betaOffset = parsedBetaOffset
                tcOffset = parsedTcOffset
            }
        }
        val scalingListDataPresent = bits.readBit() != 0
        if (scalingListDataPresent) skipScalingListData(bits)
        val listsModificationPresent = bits.readBit() != 0
        val log2ParallelMerge = readUe(bits, 6, "log2_parallel_merge_level_minus2")
        val sliceHeaderExtension = bits.readBit() != 0
        val extensionPresent = bits.readBit() != 0
        val extensionFlags = if (extensionPresent) bits.readBits(8) else null

        return PpsMetadata(ppsId, spsId, dependentSlices, outputFlag, extraHeaderBits,
            tilesEnabled, entropySync, uniform, numColumns, numRows, explicitColumns, explicitRows,
            columnWidths, rowHeights, loopFilter, weightedPred, weightedBipred, transquantBypass,
            loopFilterAcrossSlices, deblockingControlPresent, deblockingOverride, deblockingDisabled,
            betaOffset, tcOffset, scalingListDataPresent, listsModificationPresent,
            log2ParallelMerge, sliceHeaderExtension, extensionPresent, extensionFlags)
    }

    /**
     * Parses an explicit or inter-predicted short-term RPS and expands it to absolute POC deltas.
     * The same derived representation is used for SPS sets and the inline slice-header set.
     */
    internal fun parseShortTermRefPicSet(
        bits: HevcBitReader,
        index: Int,
        totalSets: Int,
        previous: List<ShortTermRefPicSet>,
    ): ShortTermRefPicSet {
        require(index in 0..totalSets) { "Short-term RPS index is out of range" }
        val predicted = if (index > 0) bits.readBit() != 0 else false
        if (predicted) {
            val deltaIndexMinus1 = if (index == totalSets) {
                readUe(bits, index - 1, "delta_idx_minus1")
            } else {
                0
            }
            require(deltaIndexMinus1 < index) { "Predicted RPS source index is out of range" }
            val referenceIndex = index - (deltaIndexMinus1 + 1)
            val reference = previous.getOrNull(referenceIndex)
                ?: throw IllegalArgumentException("Predicted RPS source set is unavailable")
            val numDeltaPocs = reference.negative.size + reference.positive.size
            require(numDeltaPocs <= 32) { "Predicted RPS source exceeds supported reference count" }

            val deltaRpsSign = bits.readBit() != 0
            val absDeltaRps = readUe(bits, 32767, "abs_delta_rps_minus1") + 1
            val deltaRps = if (deltaRpsSign) -absDeltaRps else absDeltaRps

            val usedByCurrent = BooleanArray(numDeltaPocs + 1)
            val useDelta = BooleanArray(numDeltaPocs + 1)
            for (i in 0..numDeltaPocs) {
                usedByCurrent[i] = bits.readBit() != 0
                useDelta[i] = if (usedByCurrent[i]) true else bits.readBit() != 0
            }
            return derivePredictedShortTermRefPicSet(reference, deltaRps, usedByCurrent, useDelta)
        }

        val negativeCount = readUe(bits, 16, "num_negative_pics")
        val positiveCount = readUe(bits, 16, "num_positive_pics")
        require(negativeCount + positiveCount <= 32) { "Short-term RPS exceeds supported reference count" }

        val negative = mutableListOf<ReferenceDelta>()
        var negativePoc = 0
        repeat(negativeCount) {
            negativePoc -= readUe(bits, 32767, "delta_poc_s0_minus1") + 1
            negative += ReferenceDelta(negativePoc, bits.readBit() != 0)
        }
        val positive = mutableListOf<ReferenceDelta>()
        var positivePoc = 0
        repeat(positiveCount) {
            positivePoc += readUe(bits, 32767, "delta_poc_s1_minus1") + 1
            positive += ReferenceDelta(positivePoc, bits.readBit() != 0)
        }
        return ShortTermRefPicSet(negative, positive)
    }

    /**
     * Implements the HEVC 7.4.8 inter-RPS derivation order. The order matters: negative
     * deltas are retained nearest-to-farthest from the current picture, followed by the
     * positive list in ascending order. Entries with use_delta_flag == 0 are discarded.
     */
    private fun derivePredictedShortTermRefPicSet(
        reference: ShortTermRefPicSet,
        deltaRps: Int,
        usedByCurrent: BooleanArray,
        useDelta: BooleanArray,
    ): ShortTermRefPicSet {
        val negative = mutableListOf<ReferenceDelta>()
        val positive = mutableListOf<ReferenceDelta>()
        val numNegative = reference.negative.size
        val numPositive = reference.positive.size
        val numDeltaPocs = numNegative + numPositive
        require(usedByCurrent.size == numDeltaPocs + 1 && useDelta.size == numDeltaPocs + 1) {
            "Predicted RPS flags do not match source reference count"
        }

        // Source positive deltas, shifted below zero, are first in the derived negative set.
        for (j in numPositive - 1 downTo 0) {
            val index = numNegative + j
            val deltaPoc = reference.positive[j].deltaPoc + deltaRps
            if (deltaPoc < 0 && useDelta[index]) {
                negative += ReferenceDelta(deltaPoc, usedByCurrent[index])
            }
        }
        if (deltaRps < 0 && useDelta[numDeltaPocs]) {
            negative += ReferenceDelta(deltaRps, usedByCurrent[numDeltaPocs])
        }
        for (j in 0 until numNegative) {
            val deltaPoc = reference.negative[j].deltaPoc + deltaRps
            if (deltaPoc < 0 && useDelta[j]) {
                negative += ReferenceDelta(deltaPoc, usedByCurrent[j])
            }
        }

        // Source negative deltas, shifted above zero, are first in the derived positive set.
        for (j in numNegative - 1 downTo 0) {
            val deltaPoc = reference.negative[j].deltaPoc + deltaRps
            if (deltaPoc > 0 && useDelta[j]) {
                positive += ReferenceDelta(deltaPoc, usedByCurrent[j])
            }
        }
        if (deltaRps > 0 && useDelta[numDeltaPocs]) {
            positive += ReferenceDelta(deltaRps, usedByCurrent[numDeltaPocs])
        }
        for (j in 0 until numPositive) {
            val index = numNegative + j
            val deltaPoc = reference.positive[j].deltaPoc + deltaRps
            if (deltaPoc > 0 && useDelta[index]) {
                positive += ReferenceDelta(deltaPoc, usedByCurrent[index])
            }
        }

        return ShortTermRefPicSet(negative, positive)
    }

    private fun skipScalingListData(bits: HevcBitReader) {
        for (sizeId in 0..3) {
            val matrixStep = if (sizeId == 3) 3 else 1
            var matrixId = 0
            while (matrixId < 6) {
                if (bits.readBit() == 0) {
                    readUe(bits, 63, "scaling_list_pred_matrix_id_delta")
                } else {
                    val coefficientCount = minOf(64, 1 shl (4 + (sizeId shl 1)))
                    if (sizeId > 1) bits.readSignedExpGolomb()
                    repeat(coefficientCount) { bits.readSignedExpGolomb() }
                }
                matrixId += matrixStep
            }
        }
    }

    private fun readerFor(nal: ByteArray, expectedType: Int): HevcBitReader {
        val header = HevcBitstream.parseNalHeader(nal)
        require(header.type == expectedType) { "Expected HEVC NAL type $expectedType, got ${header.type}" }
        require(header.layerId == 0) { "Non-zero HEVC layer IDs are not supported by this parser" }
        return HevcBitReader(HevcBitstream.ebspToRbsp(nal.copyOfRange(2, nal.size)))
    }

    private fun parseProfileTierLevelFields(bits: HevcBitReader): ProfileTierLevelFields {
        val profileSpace = bits.readBits(2)
        val tierFlag = bits.readBit() != 0
        val profileIdc = bits.readBits(5)
        val compatibility = bits.readBits(32).toLong() and 0xffffffffL
        val constraints = ((bits.readBits(32).toLong() and 0xffffffffL) shl 16) or bits.readBits(16).toLong()
        return ProfileTierLevelFields(profileSpace, tierFlag, profileIdc, compatibility, constraints)
    }

    private fun parseProfileTierLevel(bits: HevcBitReader, maxSubLayersMinus1: Int): ProfileTierLevel {
        val general = parseProfileTierLevelFields(bits)
        val levelIdc = bits.readBits(8)
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = bits.readBit() != 0
            levelPresent[i] = bits.readBit() != 0
        }
        if (maxSubLayersMinus1 > 0) {
            for (i in maxSubLayersMinus1 until 8) bits.skipBits(2)
        }
        val subLayerProfiles = MutableList<ProfileTierLevelFields?>(maxSubLayersMinus1) { null }
        val subLayerLevels = MutableList<Int?>(maxSubLayersMinus1) { null }
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) subLayerProfiles[i] = parseProfileTierLevelFields(bits)
            if (levelPresent[i]) subLayerLevels[i] = bits.readBits(8)
        }
        return ProfileTierLevel(
            general.profileSpace, general.tierFlag, general.profileIdc,
            general.compatibilityFlags, general.constraintIndicatorFlags, levelIdc,
            profilePresent.toList(), levelPresent.toList(), subLayerProfiles, subLayerLevels
        )
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
