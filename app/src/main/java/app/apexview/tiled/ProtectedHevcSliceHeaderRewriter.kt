package app.apexview.tiled

/**
 * Applies the existing HEVC slice-header rewrite to a subsample-encrypted NAL.
 *
 * The complete NAL is supplied to the byte-preserving rewriter so that its opaque tail can be
 * copied unchanged. Syntax parsing is limited to the verified clear prefix. The returned map
 * describes the rewritten NAL's exact clear/encrypted byte layout; the NAL length prefix is not
 * included and must be written as clear by the enclosing CMAF sample builder.
 *
 * This does not decrypt media, change encrypted payload bytes, or build senc/saiz/saio boxes.
 */
internal object ProtectedHevcSliceHeaderRewriter {
    data class Result(
        val nal: ByteArray,
        val protectionRanges: List<ProtectedHevcSampleLayout.ProtectionRange>,
        val destinationAddress: Int,
        val sourceTailOffset: Int,
        val outputTailOffset: Int,
        val copiedTailBytes: Int,
        val encryptedByteCount: Long
    )

    fun rewrite(
        sample: ByteArray,
        layout: ProtectedHevcSampleLayout.Report,
        accessUnit: ProtectedHevcAccessUnitInspector.Report,
        nalIndex: Int,
        sourceSps: HevcParameterSets.SpsMetadata,
        sourcePps: HevcParameterSets.PpsMetadata,
        outputSps: HevcParameterSets.SpsMetadata,
        outputPps: HevcParameterSets.PpsMetadata,
        destinationAddress: Int,
        loopFilterAcrossSlicesOverride: Boolean? = null,
        context: String = "Protected HEVC slice rewrite"
    ): Result {
        require(sample.isNotEmpty() && sample.size == layout.sampleSizeBytes &&
            sample.size == accessUnit.sampleSizeBytes) {
            "$context: sample size does not match its inspected metadata"
        }
        val mapped = layout.nals.singleOrNull { it.index == nalIndex }
            ?: throw IllegalArgumentException("$context: NAL $nalIndex is missing or duplicated in the subsample layout")
        val inspectedNal = accessUnit.nals.singleOrNull { it.index == nalIndex }
            ?: throw IllegalArgumentException("$context: NAL $nalIndex is missing or duplicated in the access-unit report")
        val slice = accessUnit.slices.singleOrNull { it.nalIndex == nalIndex }
            ?: throw IllegalArgumentException("$context: NAL $nalIndex is not one independent slice")

        require(mapped.nalLength == inspectedNal.nalLength &&
            mapped.nalType == inspectedNal.nalType &&
            mapped.layerId == inspectedNal.layerId &&
            mapped.temporalIdPlus1 == inspectedNal.temporalIdPlus1 &&
            mapped.clearNalPrefixBytes == inspectedNal.clearNalPrefixBytes) {
            "$context: access-unit inspection does not match the selected NAL layout"
        }
        require(slice.readyForCurrentHeaderRewriter) {
            "$context: NAL $nalIndex does not expose the three clear CABAC bytes required by the rewriter"
        }
        require(mapped.protectionRanges.isNotEmpty()) {
            "$context: NAL $nalIndex has no detailed clear/encrypted byte map"
        }

        val sourceNalEnd = Math.addExact(mapped.nalOffset, mapped.nalLength)
        require(mapped.nalOffset >= 0 && sourceNalEnd <= sample.size) {
            "$context: NAL $nalIndex is outside the CMAF sample"
        }
        val sourceNal = sample.copyOfRange(mapped.nalOffset, sourceNalEnd)
        val rewritten = HevcSliceHeaderRewriter.rewrite(
            nal = sourceNal,
            sourceSps = sourceSps,
            sourcePps = sourcePps,
            outputSps = outputSps,
            outputPps = outputPps,
            destinationAddress = destinationAddress,
            clearNalBytes = mapped.clearNalPrefixBytes,
            loopFilterAcrossSlicesOverride = loopFilterAcrossSlicesOverride
        )

        require(rewritten.sourceTailOffset == slice.thirdClearCabacByteOffset) {
            "$context: slice rewriter's clear-tail boundary differs from the protected access-unit inspection"
        }
        require(rewritten.sourceTailOffset >= 2 &&
            rewritten.sourceTailOffset < mapped.clearNalPrefixBytes &&
            rewritten.sourceTailOffset + 1 <= sourceNal.size) {
            "$context: copied CABAC tail does not begin at a verified clear byte"
        }
        require(isFullyClear(mapped.protectionRanges, 0, rewritten.sourceTailOffset + 1)) {
            "$context: rewritten CABAC prefix crosses into encrypted bytes"
        }

        val outputTailOffset = rewritten.nal.size - rewritten.copiedTailBytes
        require(outputTailOffset >= 2 && rewritten.copiedTailBytes > 0 &&
            outputTailOffset + rewritten.copiedTailBytes == rewritten.nal.size) {
            "$context: rewritten NAL tail bounds are inconsistent"
        }
        require(rewritten.copiedTailBytes == sourceNal.size - rewritten.sourceTailOffset) {
            "$context: rewriter did not preserve the complete source NAL tail"
        }
        for (index in 0 until rewritten.copiedTailBytes) {
            require(
                rewritten.nal[outputTailOffset + index] ==
                    sourceNal[rewritten.sourceTailOffset + index]
            ) {
                "$context: copied clear/encrypted NAL tail changed at byte $index"
            }
        }

        val rebuiltRanges = ArrayList<ProtectedHevcSampleLayout.ProtectionRange>()
        var outputCoveredUntil = 0

        fun appendRange(offset: Int, length: Int, isClear: Boolean) {
            require(length > 0 && offset == outputCoveredUntil) {
                "$context: rewritten protection map contains a gap or overlap"
            }
            val previous = rebuiltRanges.lastOrNull()
            // Keep adjacent encrypted ranges distinct. Each source CENC subsample boundary
            // represents an independent CBCS chain and must survive the header rewrite.
            if (isClear && previous != null && previous.isClear &&
                previous.endExclusive == offset
            ) {
                rebuiltRanges[rebuiltRanges.lastIndex] = previous.copy(
                    lengthBytes = Math.addExact(previous.lengthBytes, length)
                )
            } else {
                rebuiltRanges += ProtectedHevcSampleLayout.ProtectionRange(
                    offset = offset,
                    lengthBytes = length,
                    isClear = isClear
                )
            }
            outputCoveredUntil = Math.addExact(outputCoveredUntil, length)
        }

        // The rewritten header, its first two CABAC bytes, and the copied third CABAC byte are
        // all clear. The byte following that third CABAC byte is where source protection mapping
        // resumes, shifted by the header rewrite's size delta.
        appendRange(0, outputTailOffset + 1, isClear = true)
        val sourceMapResume = rewritten.sourceTailOffset + 1
        mapped.protectionRanges.forEach { range ->
            val intersectionStart = maxOf(sourceMapResume, range.offset)
            val intersectionEnd = minOf(sourceNal.size, range.endExclusive)
            if (intersectionStart < intersectionEnd) {
                val outputOffset = outputTailOffset + 1 +
                    (intersectionStart - sourceMapResume)
                appendRange(
                    offset = outputOffset,
                    length = intersectionEnd - intersectionStart,
                    isClear = range.isClear
                )
            }
        }
        require(outputCoveredUntil == rewritten.nal.size) {
            "$context: rewritten protection map does not cover the complete NAL"
        }
        require(rebuiltRanges.firstOrNull()?.offset == 0 &&
            rebuiltRanges.lastOrNull()?.endExclusive == rewritten.nal.size) {
            "$context: rewritten protection map has invalid outer bounds"
        }

        val encryptedBytes = rebuiltRanges.asSequence()
            .filterNot { it.isClear }
            .sumOf { it.lengthBytes.toLong() }
        return Result(
            nal = rewritten.nal,
            protectionRanges = rebuiltRanges,
            destinationAddress = rewritten.destinationAddress,
            sourceTailOffset = rewritten.sourceTailOffset,
            outputTailOffset = outputTailOffset,
            copiedTailBytes = rewritten.copiedTailBytes,
            encryptedByteCount = encryptedBytes
        )
    }

    private fun isFullyClear(
        ranges: List<ProtectedHevcSampleLayout.ProtectionRange>,
        start: Int,
        endExclusive: Int
    ): Boolean {
        if (start < 0 || endExclusive <= start) return false
        var coveredUntil = start
        ranges.forEach { range ->
            val intersectionStart = maxOf(start, range.offset)
            val intersectionEnd = minOf(endExclusive, range.endExclusive)
            if (intersectionStart < intersectionEnd) {
                if (!range.isClear || intersectionStart != coveredUntil) return false
                coveredUntil = intersectionEnd
            }
        }
        return coveredUntil == endExclusive
    }
}
