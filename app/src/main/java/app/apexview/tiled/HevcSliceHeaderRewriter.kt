package app.apexview.tiled

/**
 * Rewrites a clear HEVC slice header for one cell of a newly constructed output picture.
 *
 * CABAC is not decoded. The rewritten header and two clear CABAC bytes are escaped again to
 * establish a valid emulation-prevention boundary. The remaining source EBSP bytes are copied
 * unchanged. The caller must supply a verified clear prefix length from its CMAF subsample map.
 */
internal object HevcSliceHeaderRewriter {
    data class Result(
        val nal: ByteArray,
        val destinationAddress: Int,
        val sourceHeaderBytes: Int,
        val outputHeaderBytes: Int,
        val sourceTailOffset: Int,
        val copiedTailBytes: Int,
        val reencodedClearPayloadBytes: Int
    )

    private data class ClearRbsp(
        val bytes: ByteArray,
        val sourceTailOffset: Int
    )

    fun rewrite(
        nal: ByteArray,
        sourceSps: HevcParameterSets.SpsMetadata,
        sourcePps: HevcParameterSets.PpsMetadata,
        outputSps: HevcParameterSets.SpsMetadata,
        outputPps: HevcParameterSets.PpsMetadata,
        destinationAddress: Int,
        clearNalBytes: Int = nal.size,
        loopFilterAcrossSlicesOverride: Boolean? = null
    ): Result {
        val nalHeader = HevcBitstream.readNalHeader(nal)
        require(nalHeader.type in 0..21) { "Unsupported VCL NAL type" }
        require(nalHeader.layerId == 0) { "Non-zero HEVC layers are not supported" }
        require(sourcePps.spsId == sourceSps.spsId) { "Source PPS/SPS identity mismatch" }
        require(outputPps.spsId == outputSps.spsId) { "Output PPS/SPS identity mismatch" }
        require(!sourceSps.spsExtensionPresent && !outputSps.spsExtensionPresent) {
            "SPS extensions are not supported by the slice rewriter"
        }
        require(!sourcePps.extensionPresent && !outputPps.extensionPresent) {
            "PPS extensions are not supported by the slice rewriter"
        }
        require(clearNalBytes in 3..nal.size) { "Invalid clear NAL prefix length" }
        requireCompatibleSps(sourceSps, outputSps)
        requireCompatiblePps(sourcePps, outputPps)
        require(!sourceSps.scalingListDataPresent && !outputSps.scalingListDataPresent) {
            "SPS scaling-list data is not supported by the slice rewriter"
        }
        require(!sourceSps.pcmEnabled && !outputSps.pcmEnabled) {
            "SPS PCM coding is not supported by the slice rewriter"
        }
        require(!sourcePps.scalingListDataPresent && !outputPps.scalingListDataPresent) {
            "PPS scaling-list data is not supported by the slice rewriter"
        }
        require(!sourcePps.entropyCodingSyncEnabled && !outputPps.entropyCodingSyncEnabled) {
            "WPP/entropy-coding-sync slices are not supported"
        }
        require(!sourcePps.sliceSegmentHeaderExtensionPresent &&
            !outputPps.sliceSegmentHeaderExtensionPresent) {
            "Slice-header extensions are not supported"
        }

        val parsed = HevcSliceHeaderParser.parse(nal, sourceSps, sourcePps, clearNalBytes)
        require(!parsed.dependentSliceSegment) { "Dependent slice segments are not supported" }
        require(parsed.entryPointOffsets.isEmpty()) {
            "Non-zero tile/WPP entry-point offsets are not supported"
        }
        require(loopFilterAcrossSlicesOverride == null || parsed.loopFilterAcrossSlicesFieldBit != null) {
            "A loop-filter override was requested but the source slice has no loop_filter_across_slices_enabled_flag"
        }
        require(destinationAddress >= 0) { "Destination CTB address must not be negative" }
        val outputPictureCtbs = Math.multiplyExact(outputSps.picWidthInCtbs, outputSps.picHeightInCtbs)
        require(destinationAddress < outputPictureCtbs) {
            "Destination CTB address exceeds the output picture"
        }

        require(parsed.bitsConsumed % 8 == 0) { "Source slice header is not byte aligned" }
        val sourceHeaderBytes = parsed.bitsConsumed / 8
        // Only the slice header and three CABAC bytes are needed here. An all-clear
        // video NAL may be hundreds of kilobytes, so never allocate or box an RBSP
        // byte and source-offset entry for the entire compressed picture.
        val clearRbsp = decodeClearPrefix(nal, clearNalBytes, sourceHeaderBytes + 3)
        val rbsp = clearRbsp.bytes
        require(parsed.sourceAddressEndBit <= parsed.entryPointFieldStartBit &&
            parsed.entryPointFieldStartBit <= parsed.entryPointFieldEndBit &&
            parsed.entryPointFieldEndBit <= parsed.alignmentStartBit) {
            "Slice-header syntax offsets are inconsistent"
        }

        val sourceBits = HevcBitReader(rbsp)
        val sourceFirst = sourceBits.readBit() != 0
        if (nalHeader.type in 16..21) sourceBits.readBit()
        sourceBits.readUnsignedExpGolomb()
        if (!sourceFirst) {
            if (sourcePps.dependentSliceSegmentsEnabled) {
                require(sourceBits.readBit() == 0) { "Dependent slice segments are not supported" }
            }
            val sourcePictureCtbs = Math.multiplyExact(sourceSps.picWidthInCtbs, sourceSps.picHeightInCtbs)
            val sourceAddressBits = ceilLog2(sourcePictureCtbs)
            if (sourceAddressBits > 0) sourceBits.readBits(sourceAddressBits)
        }
        require(sourceBits.positionBits == parsed.sourceAddressEndBit) {
            "Source address boundary does not match the parsed header"
        }

        val outputAddressBits = ceilLog2(outputPictureCtbs)
        val writer = BitWriter()
        writer.writeBit(if (destinationAddress == 0) 1 else 0)
        if (nalHeader.type in 16..21) {
            writer.writeBit(if (parsed.noOutputOfPriorPictures == true) 1 else 0)
        }
        writer.writeUnsignedExpGolomb(outputPps.ppsId)
        if (destinationAddress != 0) {
            if (outputPps.dependentSliceSegmentsEnabled) writer.writeBit(0)
            if (outputAddressBits > 0) writer.writeBits(destinationAddress, outputAddressBits)
        }

        // Preserve every parsed coding field after the address, including POC/RPS, prediction
        // and deblocking syntax. The Tizen grid policy may override the per-slice loop-filter
        // gate so independent feeds are never filtered across an output seam.
        val loopFilterBit = parsed.loopFilterAcrossSlicesFieldBit
        if (loopFilterAcrossSlicesOverride != null && loopFilterBit != null) {
            require(loopFilterBit in parsed.sourceAddressEndBit until parsed.entryPointFieldStartBit) {
                "Loop-filter flag bit is outside the slice-header coding syntax"
            }
            writer.copyBits(rbsp, parsed.sourceAddressEndBit, loopFilterBit)
            writer.writeBit(if (loopFilterAcrossSlicesOverride) 1 else 0)
            writer.copyBits(rbsp, loopFilterBit + 1, parsed.entryPointFieldStartBit)
        } else {
            writer.copyBits(rbsp, parsed.sourceAddressEndBit, parsed.entryPointFieldStartBit)
        }

        // The output grid has one independently coded slice per cell, hence no entry-point
        // substreams inside a cell. Source entry-point syntax is replaced, not carried over.
        if (outputPps.tilesEnabled || outputPps.entropyCodingSyncEnabled) {
            writer.writeUnsignedExpGolomb(0)
        }
        writer.copyBits(rbsp, parsed.entryPointFieldEndBit, parsed.alignmentStartBit)
        writer.writeBit(1)
        while (writer.bitCount % 8 != 0) writer.writeBit(0)
        val outputHeaderRbsp = writer.toByteArray()

        val thirdCabacByte = rbsp[sourceHeaderBytes + 2].toInt() and 0xff
        val headerAndCabac = ByteArray(outputHeaderRbsp.size + 2)
        outputHeaderRbsp.copyInto(headerAndCabac)
        rbsp.copyInto(headerAndCabac, outputHeaderRbsp.size, sourceHeaderBytes, sourceHeaderBytes + 2)
        val escapedHeaderAndPayloadPrefix = escapeRbsp(headerAndCabac, thirdCabacByte)
        val sourceTailOffset = clearRbsp.sourceTailOffset
        require(sourceTailOffset in 2 until nal.size) { "CABAC tail is outside the source NAL" }
        val outputNal = ByteArray(2 + escapedHeaderAndPayloadPrefix.size + nal.size - sourceTailOffset)
        nal.copyInto(outputNal, 0, 0, 2)
        escapedHeaderAndPayloadPrefix.copyInto(outputNal, 2)
        nal.copyInto(outputNal, 2 + escapedHeaderAndPayloadPrefix.size, sourceTailOffset)

        return Result(
            nal = outputNal,
            destinationAddress = destinationAddress,
            sourceHeaderBytes = sourceHeaderBytes,
            outputHeaderBytes = outputHeaderRbsp.size,
            sourceTailOffset = sourceTailOffset,
            copiedTailBytes = nal.size - sourceTailOffset,
            reencodedClearPayloadBytes = 2
        )
    }

    private fun requireCompatibleSps(
        source: HevcParameterSets.SpsMetadata,
        output: HevcParameterSets.SpsMetadata
    ) {
        val sourceProfile = source.profileTierLevel.copy(levelIdc = 0)
        val outputProfile = output.profileTierLevel.copy(levelIdc = 0)
        require(
            source.maxSubLayers == output.maxSubLayers &&
                source.temporalIdNesting == output.temporalIdNesting &&
                source.chromaFormatIdc == output.chromaFormatIdc &&
                source.separateColourPlane == output.separateColourPlane &&
                source.log2MaxPicOrderCntLsb == output.log2MaxPicOrderCntLsb &&
                source.log2CtbSize == output.log2CtbSize &&
                source.ctbSize == output.ctbSize &&
                source.sampleAdaptiveOffsetEnabled == output.sampleAdaptiveOffsetEnabled &&
                source.temporalMvpEnabled == output.temporalMvpEnabled &&
                source.longTermRefPicsPresent == output.longTermRefPicsPresent &&
                source.longTermRefPics == output.longTermRefPics &&
                source.shortTermRefPicSets == output.shortTermRefPicSets &&
                source.bitDepthLuma == output.bitDepthLuma &&
                source.bitDepthChroma == output.bitDepthChroma &&
                source.scalingListEnabled == output.scalingListEnabled &&
                source.scalingListDataPresent == output.scalingListDataPresent &&
                source.ampEnabled == output.ampEnabled &&
                source.pcmEnabled == output.pcmEnabled &&
                source.strongIntraSmoothingEnabled == output.strongIntraSmoothingEnabled &&
                source.maxDecPicBuffering == output.maxDecPicBuffering &&
                source.maxNumReorderPics == output.maxNumReorderPics &&
                source.maxLatencyIncrease == output.maxLatencyIncrease &&
                sourceProfile == outputProfile
        ) { "Output SPS changes source slice-coding semantics" }
    }

    private fun requireCompatiblePps(
        source: HevcParameterSets.PpsMetadata,
        output: HevcParameterSets.PpsMetadata
    ) {
        require(
            source.dependentSliceSegmentsEnabled == output.dependentSliceSegmentsEnabled &&
                source.outputFlagPresent == output.outputFlagPresent &&
                source.signDataHidingEnabled == output.signDataHidingEnabled &&
                source.constrainedIntraPredictionEnabled == output.constrainedIntraPredictionEnabled &&
                source.transformSkipEnabled == output.transformSkipEnabled &&
                source.cuQpDeltaEnabled == output.cuQpDeltaEnabled &&
                source.diffCuQpDeltaDepth == output.diffCuQpDeltaDepth &&
                source.chromaQpOffsetCb == output.chromaQpOffsetCb &&
                source.chromaQpOffsetCr == output.chromaQpOffsetCr &&
                source.extraSliceHeaderBits == output.extraSliceHeaderBits &&
                source.cabacInitPresent == output.cabacInitPresent &&
                source.numRefIdxL0DefaultActiveMinus1 == output.numRefIdxL0DefaultActiveMinus1 &&
                source.numRefIdxL1DefaultActiveMinus1 == output.numRefIdxL1DefaultActiveMinus1 &&
                source.initQpMinus26 == output.initQpMinus26 &&
                source.sliceChromaQpOffsetsPresent == output.sliceChromaQpOffsetsPresent &&
                source.weightedPredictionEnabled == output.weightedPredictionEnabled &&
                source.weightedBipredictionEnabled == output.weightedBipredictionEnabled &&
                source.transquantBypassEnabled == output.transquantBypassEnabled &&
                source.loopFilterAcrossSlicesEnabled == output.loopFilterAcrossSlicesEnabled &&
                source.deblockingFilterControlPresent == output.deblockingFilterControlPresent &&
                source.deblockingFilterOverrideEnabled == output.deblockingFilterOverrideEnabled &&
                source.deblockingFilterDisabled == output.deblockingFilterDisabled &&
                source.betaOffsetDiv2 == output.betaOffsetDiv2 &&
                source.tcOffsetDiv2 == output.tcOffsetDiv2 &&
                source.scalingListDataPresent == output.scalingListDataPresent &&
                source.listsModificationPresent == output.listsModificationPresent &&
                source.log2ParallelMergeLevelMinus2 == output.log2ParallelMergeLevelMinus2 &&
                source.sliceSegmentHeaderExtensionPresent == output.sliceSegmentHeaderExtensionPresent
        ) { "Output PPS changes source slice-header semantics" }
    }

    private fun decodeClearPrefix(nal: ByteArray, clearNalBytes: Int, requiredBytes: Int): ClearRbsp {
        require(requiredBytes in 3..clearNalBytes - 2) {
            "At least three clear CABAC bytes are required after the slice header"
        }
        val decoded = ByteArray(requiredBytes)
        var decodedCount = 0
        var tailOffset = -1
        var zeroCount = 0
        var index = 2
        while (index < clearNalBytes) {
            val value = nal[index].toInt() and 0xff
            if (zeroCount >= 2 && value == 3) {
                require(index + 1 < clearNalBytes) {
                    "Emulation-prevention byte is truncated at the clear/encrypted boundary"
                }
                val next = nal[index + 1].toInt() and 0xff
                require(next <= 3) { "Invalid emulation-prevention sequence in clear slice header" }
                zeroCount = 0
                index++
                continue
            }
            if (decodedCount < requiredBytes) {
                decoded[decodedCount] = nal[index]
                if (decodedCount == requiredBytes - 1) tailOffset = index
            }
            decodedCount++
            zeroCount = if (value == 0) zeroCount + 1 else 0
            index++
        }
        require(decodedCount >= requiredBytes) {
            "At least three clear CABAC bytes are required after the slice header"
        }
        return ClearRbsp(decoded, tailOffset)
    }

    private fun escapeRbsp(rbsp: ByteArray, nextRbspByte: Int): ByteArray {
        // A short header may contain multiple prevention bytes, but does not need
        // boxed ArrayList<Byte> growth and conversion on every video frame.
        val out = ByteArray(rbsp.size + (rbsp.size / 2) + 2)
        var written = 0
        var zeroCount = 0
        rbsp.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (zeroCount >= 2 && value <= 3) {
                out[written++] = 3.toByte()
                zeroCount = 0
            }
            out[written++] = byte
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        if (zeroCount >= 2 && nextRbspByte in 0..3) out[written++] = 3.toByte()
        return out.copyOf(written)
    }

    private fun ceilLog2(value: Int): Int {
        require(value > 0)
        return if (value <= 1) 0 else 32 - Integer.numberOfLeadingZeros(value - 1)
    }

    private class BitWriter {
        private var bytes = ByteArray(64)
        var bitCount: Int = 0
            private set

        fun writeBit(value: Int) {
            require(value == 0 || value == 1)
            if (bitCount / 8 >= bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            if (value != 0) {
                val index = bitCount / 8
                bytes[index] = (bytes[index].toInt() or (1 shl (7 - bitCount % 8))).toByte()
            }
            bitCount++
        }

        fun writeBits(value: Int, count: Int) {
            require(count in 0..32)
            require(count == 32 || value ushr count == 0) { "Value does not fit output field" }
            for (index in count - 1 downTo 0) writeBit((value ushr index) and 1)
        }

        fun writeUnsignedExpGolomb(value: Int) {
            require(value >= 0)
            val codeNum = value.toLong() + 1L
            val width = 64 - java.lang.Long.numberOfLeadingZeros(codeNum)
            repeat(width - 1) { writeBit(0) }
            for (index in width - 1 downTo 0) {
                writeBit(((codeNum ushr index) and 1L).toInt())
            }
        }

        fun copyBits(source: ByteArray, from: Int, to: Int) {
            require(from in 0..to && to <= source.size * 8) { "Invalid bit copy range" }
            for (position in from until to) {
                val value = (source[position / 8].toInt() ushr (7 - position % 8)) and 1
                writeBit(value)
            }
        }

        fun toByteArray(): ByteArray {
            require(bitCount % 8 == 0) { "Output slice header must be byte aligned" }
            return bytes.copyOf(bitCount / 8)
        }
    }
}
