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

    /**
     * The new header is owned; the unchanged CABAC tail is borrowed from an
     * already validated input NAL. Clear and inspected protected writers may
     * retain this view only synchronously until their final fragment is written.
     */
    internal class Parts internal constructor(
        private var prefix: ByteArray,
        private val backing: ByteArray,
        private val tailStart: Int,
        val copiedTailBytes: Int,
        val destinationAddress: Int,
        val sourceHeaderBytes: Int,
        val outputHeaderBytes: Int,
        val sourceTailOffset: Int,
        val reencodedClearPayloadBytes: Int
    ) : AutoCloseable {
        private var closed = false
        val size = Math.addExact(prefix.size, copiedTailBytes)
        init {
            require(prefix.size >= 2 && tailStart >= 0 && copiedTailBytes > 0 &&
                tailStart <= backing.size && copiedTailBytes <= backing.size - tailStart) {
                "Rewritten HEVC tail range is out of bounds"
            }
        }

        fun materialize(limit: Int = size): ByteArray {
            check(!closed)
            require(limit in 1..size)
            val bytes = ByteArray(limit)
            val prefixCount = minOf(limit, prefix.size)
            prefix.copyInto(bytes, 0, 0, prefixCount)
            if (limit > prefixCount)
                backing.copyInto(bytes, prefixCount, tailStart, tailStart + limit - prefixCount)
            return bytes
        }

        /** Transfer an independently generated clear prefix only on exact
         * identity with the already validated JVM reference and splice. */
        internal fun adoptVerifiedNativePrefix(candidate: ByteArray, tailOffset: Int): Boolean {
            check(!closed)
            if (tailOffset != sourceTailOffset || !candidate.contentEquals(prefix)) return false
            prefix.fill(0)
            prefix = candidate
            return true
        }

        fun header(): HevcBitstream.NalHeader {
            check(!closed)
            return HevcBitstream.readNalHeader(prefix)
        }

        fun copyInto(output: ByteArray, offset: Int) {
            check(!closed)
            require(offset >= 0 && offset <= output.size - size)
            prefix.copyInto(output, offset)
            backing.copyInto(output, offset + prefix.size, tailStart, tailStart + copiedTailBytes)
        }

        /** Compare only the unchanged byte range, never interpret ciphertext. */
        fun verifyCopiedTail(output: ByteArray, offset: Int) {
            check(!closed)
            require(offset >= 0 && offset <= output.size - size)
            for (i in 0 until copiedTailBytes) {
                require(output[offset + prefix.size + i] == backing[tailStart + i]) {
                    "Rewritten HEVC tail changed at byte $i"
                }
            }
        }

        fun writeTo(output: java.nio.ByteBuffer) {
            check(!closed)
            output.put(prefix)
            output.put(backing, tailStart, copiedTailBytes)
        }

        /** Preserve the original parser's 8194-byte fast path and full fallback. */
        fun verifyHeader(sps: HevcParameterSets.SpsMetadata,
                         pps: HevcParameterSets.PpsMetadata): HevcSliceHeaderParser.SliceHeader {
            val length = minOf(size, 8194)
            val prefixBytes = materialize(length)
            try {
                return try {
                    HevcSliceHeaderParser.parse(prefixBytes, sps, pps)
                } catch (failure: IllegalArgumentException) {
                    if (length == size) throw failure
                    val all = materialize()
                    try { HevcSliceHeaderParser.parse(all, sps, pps) } finally { all.fill(0) }
                }
            } finally { prefixBytes.fill(0) }
        }

        override fun close() {
            if (closed) return
            closed = true
            prefix.fill(0) // Source ownership stays with the original CMAF caller.
        }
    }

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
        loopFilterAcrossSlicesOverride: Boolean? = null,
        parsedSourceHeader: HevcSliceHeaderParser.SliceHeader? = null,
        /** Preserve opaque CBCS CABAC bytes when only the byte-aligned slice header is clear. */
        preserveOpaqueCabacTail: Boolean = false,
        sourceOffset: Int = 0,
        sourceLength: Int = nal.size
    ): Result {
        val parts = rewriteToParts(nal, sourceSps, sourcePps, outputSps, outputPps,
            destinationAddress, clearNalBytes, loopFilterAcrossSlicesOverride,
            parsedSourceHeader, preserveOpaqueCabacTail, sourceOffset, sourceLength)
        try {
            return Result(parts.materialize(), parts.destinationAddress, parts.sourceHeaderBytes,
                parts.outputHeaderBytes, parts.sourceTailOffset, parts.copiedTailBytes,
                parts.reencodedClearPayloadBytes)
        } finally { parts.close() }
    }

    fun rewriteClearParts(
        nal: HevcBitstream.NalSpan,
        sourceSps: HevcParameterSets.SpsMetadata,
        sourcePps: HevcParameterSets.PpsMetadata,
        outputSps: HevcParameterSets.SpsMetadata,
        outputPps: HevcParameterSets.PpsMetadata,
        destinationAddress: Int,
        loopFilterAcrossSlicesOverride: Boolean?,
        parsedSourceHeader: HevcSliceHeaderParser.SliceHeader
    ): Parts = rewriteToParts(nal.backing, sourceSps, sourcePps, outputSps, outputPps,
        destinationAddress, nal.length, loopFilterAcrossSlicesOverride, parsedSourceHeader,
        false, nal.offset, nal.length)


    /**
     * Only the protected rewriter calls this with a complete, independently
     * checked per-NAL subsample map. Syntax reads remain inside the clear
     * prefix, and the source tail is carried unchanged into final output.
     * The general range API below still rejects partially clear borrowed NALs.
     */
    fun rewriteInspectedProtectedParts(
        sample: ByteArray,
        mapped: ProtectedHevcSampleLayout.NalUnit,
        sourceSps: HevcParameterSets.SpsMetadata,
        sourcePps: HevcParameterSets.PpsMetadata,
        outputSps: HevcParameterSets.SpsMetadata,
        outputPps: HevcParameterSets.PpsMetadata,
        destinationAddress: Int,
        loopFilterAcrossSlicesOverride: Boolean?,
        preserveOpaqueCabacTail: Boolean,
        nativePlan: NativeProtectedPlan? = null,
        sourceSets: List<ByteArray> = emptyList(),
        inspectedTailOffset: Int? = null,
    ): Parts {
        require(mapped.nalOffset >= 0 && mapped.nalLength >= 3 &&
            mapped.nalOffset <= sample.size - mapped.nalLength)
        var covered = 0
        var clearPrefix = 0
        var encounteredProtected = false
        for (range in mapped.protectionRanges) {
            require(range.offset == covered && range.lengthBytes > 0 &&
                range.lengthBytes <= mapped.nalLength - covered)
            covered = Math.addExact(covered, range.lengthBytes)
            if (!range.isClear) encounteredProtected = true
            else if (!encounteredProtected) clearPrefix = covered
        }
        require(covered == mapped.nalLength && clearPrefix == mapped.clearNalPrefixBytes)
        return rewriteToParts(sample, sourceSps, sourcePps, outputSps, outputPps,
            destinationAddress, clearPrefix, loopFilterAcrossSlicesOverride, null,
            preserveOpaqueCabacTail, mapped.nalOffset, mapped.nalLength, inspectedProtectedRange = true,
            nativePlan = nativePlan, nativeSourceSets = sourceSets, inspectedTailOffset = inspectedTailOffset)
    }

    private fun rewriteToParts(
        nal: ByteArray,
        sourceSps: HevcParameterSets.SpsMetadata,
        sourcePps: HevcParameterSets.PpsMetadata,
        outputSps: HevcParameterSets.SpsMetadata,
        outputPps: HevcParameterSets.PpsMetadata,
        destinationAddress: Int,
        clearNalBytes: Int = nal.size,
        loopFilterAcrossSlicesOverride: Boolean? = null,
        parsedSourceHeader: HevcSliceHeaderParser.SliceHeader? = null,
        /** Preserve opaque CBCS CABAC bytes when only the byte-aligned slice header is clear. */
        preserveOpaqueCabacTail: Boolean = false,
        sourceOffset: Int = 0,
        sourceLength: Int = nal.size,
        inspectedProtectedRange: Boolean = false,
        nativePlan: NativeProtectedPlan? = null,
        nativeSourceSets: List<ByteArray> = emptyList(),
        inspectedTailOffset: Int? = null
    ): Parts {
        val nalHeader = HevcBitstream.readNalHeader(nal, sourceOffset, sourceLength)
        // Only the dedicated subsample-validated path can borrow a partially
        // clear range. Public/general range callers retain the original gate.
        require(inspectedProtectedRange || (sourceOffset == 0 && sourceLength == nal.size) ||
            (!preserveOpaqueCabacTail && clearNalBytes == sourceLength)) {
            "Borrowed slice range requires a fully clear NAL"
        }
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
        require(clearNalBytes in 3..sourceLength) { "Invalid clear NAL prefix length" }
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

        // The grid composer already validated this same immutable NAL in its single
        // per-feed parse. Other callers still parse here and retain the full gate.
        val parsed = parsedSourceHeader
            ?: HevcSliceHeaderParser.parseRange(nal, sourceOffset, sourceLength, sourceSps, sourcePps, clearNalBytes)
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
        if (inspectedProtectedRange && nativePlan?.direct == true && inspectedTailOffset != null) {
            val native = nativePlan.directSlice(nativeSourceSets, nal, sourceOffset, clearNalBytes,
                sourceLength, inspectedTailOffset, destinationAddress, preserveOpaqueCabacTail,
                loopFilterAcrossSlicesOverride) { prefix, tail ->
                // Only the validated clear bytes are parsed. A third clear
                // CABAC byte may be needed after a terminal prevention byte.
                require(prefix.size >= 3 && prefix[0] == nal[sourceOffset] &&
                    prefix[1] == nal[sourceOffset+1])
                if (preserveOpaqueCabacTail) require(prefix.last() != 0.toByte() &&
                    nal[sourceOffset+tail-1] != 0.toByte())
                val probe = if (preserveOpaqueCabacTail) prefix.copyOf()
                    else prefix + byteArrayOf(nal[sourceOffset+tail])
                try {
                    val output = HevcSliceHeaderParser.parse(probe, outputSps, outputPps)
                    require(output.firstSliceSegmentInPicture == (destinationAddress == 0) &&
                        output.sliceSegmentAddress == destinationAddress &&
                        output.ppsId == outputPps.ppsId && !output.dependentSliceSegment &&
                        output.entryPointOffsets.isEmpty() && output.bitsConsumed % 8 == 0)
                    // Every parsed coding field must be identical except the
                    // intentionally rewritten placement, PPS, filter and bit offsets.
                    require(output.copy(firstSliceSegmentInPicture = parsed.firstSliceSegmentInPicture,
                        ppsId = parsed.ppsId, sliceSegmentAddress = parsed.sliceSegmentAddress,
                        loopFilterAcrossSlices = parsed.loopFilterAcrossSlices,
                        loopFilterAcrossSlicesFieldBit = parsed.loopFilterAcrossSlicesFieldBit,
                        bitsConsumed = parsed.bitsConsumed, sourceAddressEndBit = parsed.sourceAddressEndBit,
                        entryPointFieldStartBit = parsed.entryPointFieldStartBit,
                        entryPointFieldEndBit = parsed.entryPointFieldEndBit,
                        alignmentStartBit = parsed.alignmentStartBit) == parsed)
                    require(output.loopFilterAcrossSlices ==
                        (loopFilterAcrossSlicesOverride ?: parsed.loopFilterAcrossSlices))
                    // Verify the preserved clear CABAC bytes independently in
                    // decoded RBSP form, without reading opaque source bytes.
                    if (preserveOpaqueCabacTail) {
                        val aligned = decodeClearPrefix(probe, 0, probe.size, output.bitsConsumed/8)
                        try { require(aligned.sourceTailOffset + 1 == prefix.size) }
                        finally { aligned.bytes.fill(0) }
                    } else {
                        val before = decodeClearPrefix(nal, sourceOffset, tail+1, sourceHeaderBytes+3)
                        val after = decodeClearPrefix(probe, 0, probe.size, output.bitsConsumed/8+3)
                        try {
                            require(before.sourceTailOffset == tail &&
                                after.sourceTailOffset == prefix.size)
                            for (i in 0..2) require(before.bytes[sourceHeaderBytes+i] ==
                                after.bytes[output.bitsConsumed/8+i])
                        } finally { before.bytes.fill(0); after.bytes.fill(0) }
                    }
                    Parts(prefix, nal, sourceOffset+tail, sourceLength-tail, destinationAddress,
                        sourceHeaderBytes, output.bitsConsumed/8, tail,
                        if (preserveOpaqueCabacTail) 0 else 2)
                } finally { probe.fill(0) }
            }
            if (native != null) return native
        }
        // Only the slice header and three CABAC bytes are needed here. An all-clear
        // video NAL may be hundreds of kilobytes, so never allocate or box an RBSP
        // byte and source-offset entry for the entire compressed picture.
        // A protected sample may expose exactly the slice header and no CABAC bytes.
        // In that case only re-escape the verified clear header. Its byte_alignment()
        // terminal byte is nonzero on both sides, so the opaque EBSP tail cannot have an
        // emulation-prevention sequence straddling the splice boundary.
        val requiredClearRbspBytes = sourceHeaderBytes + if (preserveOpaqueCabacTail) 0 else 3
        val clearRbsp = decodeClearPrefix(nal, sourceOffset, clearNalBytes, requiredClearRbspBytes)
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

        val sourceTailOffset: Int
        val escapedHeaderAndPayloadPrefix: ByteArray
        val reencodedCabacBytes: Int
        if (preserveOpaqueCabacTail) {
            // Do not inspect or re-escape any byte of the protected CABAC data. The final
            // byte of an aligned HEVC slice header contains alignment_bit_equal_to_one.
            // Consequently it cannot be 0x00, before or after the header rewrite.
            sourceTailOffset = Math.addExact(clearRbsp.sourceTailOffset, 1)
            require(sourceTailOffset in 3 until sourceLength &&
                sourceTailOffset <= clearNalBytes &&
                nal[sourceOffset + sourceTailOffset - 1] != 0.toByte() &&
                outputHeaderRbsp.isNotEmpty() && outputHeaderRbsp.last() != 0.toByte()) {
                "Opaque CABAC splice requires complete clear byte-aligned slice headers"
            }
            escapedHeaderAndPayloadPrefix = escapeRbsp(outputHeaderRbsp, -1)
            require(escapedHeaderAndPayloadPrefix.isNotEmpty() &&
                escapedHeaderAndPayloadPrefix.last() != 0.toByte()) {
                "Re-escaped slice header is not safe for an opaque CABAC splice"
            }
            reencodedCabacBytes = 0
        } else {
            val thirdCabacByte = rbsp[sourceHeaderBytes + 2].toInt() and 0xff
            val headerAndCabac = ByteArray(outputHeaderRbsp.size + 2)
            outputHeaderRbsp.copyInto(headerAndCabac)
            rbsp.copyInto(headerAndCabac, outputHeaderRbsp.size,
                sourceHeaderBytes, sourceHeaderBytes + 2)
            escapedHeaderAndPayloadPrefix = escapeRbsp(headerAndCabac, thirdCabacByte)
            sourceTailOffset = clearRbsp.sourceTailOffset
            reencodedCabacBytes = 2
        }
        require(sourceTailOffset in 2 until sourceLength) { "CABAC tail is outside the source NAL" }
        val prefix = ByteArray(Math.addExact(2, escapedHeaderAndPayloadPrefix.size))
        nal.copyInto(prefix, 0, sourceOffset, sourceOffset + 2)
        escapedHeaderAndPayloadPrefix.copyInto(prefix, 2)
        return Parts(
            prefix = prefix,
            backing = nal,
            tailStart = sourceOffset + sourceTailOffset,
            destinationAddress = destinationAddress,
            sourceHeaderBytes = sourceHeaderBytes,
            outputHeaderBytes = outputHeaderRbsp.size,
            sourceTailOffset = sourceTailOffset,
            copiedTailBytes = sourceLength - sourceTailOffset,
            reencodedClearPayloadBytes = reencodedCabacBytes
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

    private fun decodeClearPrefix(nal: ByteArray, offset: Int, clearNalBytes: Int, requiredBytes: Int): ClearRbsp {
        require(requiredBytes in 1..clearNalBytes - 2) {
            "At least three clear CABAC bytes are required after the slice header"
        }
        val decoded = ByteArray(requiredBytes)
        var decodedCount = 0
        var tailOffset = -1
        var zeroCount = 0
        var index = 2
        while (index < clearNalBytes) {
            val value = nal[offset + index].toInt() and 0xff
            if (zeroCount >= 2 && value == 3) {
                require(index + 1 < clearNalBytes) {
                    "Emulation-prevention byte is truncated at the clear/encrypted boundary"
                }
                val next = nal[offset + index + 1].toInt() and 0xff
                require(next <= 3) { "Invalid emulation-prevention sequence in clear slice header" }
                zeroCount = 0
                index++
                continue
            }
            if (decodedCount < requiredBytes) {
                decoded[decodedCount] = nal[offset + index]
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
