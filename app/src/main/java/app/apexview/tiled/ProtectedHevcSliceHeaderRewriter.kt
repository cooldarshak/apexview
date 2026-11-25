package app.apexview.tiled

/**
 * Applies the existing HEVC slice-header rewrite to a subsample-encrypted NAL.
 *
 * A validated bounded source range is supplied to the byte-preserving rewriter so its opaque
 * tail can be copied unchanged. Syntax parsing is limited to the verified clear prefix. The returned map
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

    internal class Borrowed internal constructor(
        val parts: HevcSliceHeaderRewriter.Parts,
        val protectionRanges: List<ProtectedHevcSampleLayout.ProtectionRange>,
        val destinationAddress: Int,
        val sourceTailOffset: Int,
        val outputTailOffset: Int,
        val copiedTailBytes: Int,
        val encryptedByteCount: Long
    ) : AutoCloseable {
        val size get() = parts.size
        fun copyInto(output: ByteArray, offset: Int) {
            parts.copyInto(output, offset)
            parts.verifyCopiedTail(output, offset)
        }
        override fun close() = parts.close()
    }

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
        return rewriteBorrowed(sample, layout, accessUnit, nalIndex, sourceSps, sourcePps, outputSps, outputPps,
            destinationAddress, loopFilterAcrossSlicesOverride, context).use { rewritten ->
            val nal = ByteArray(rewritten.size)
            try {
                rewritten.copyInto(nal, 0)
                Result(nal, rewritten.protectionRanges, rewritten.destinationAddress,
                    rewritten.sourceTailOffset, rewritten.outputTailOffset,
                    rewritten.copiedTailBytes, rewritten.encryptedByteCount)
            } catch (failure: Throwable) {
                nal.fill(0)
                throw failure
            }
        }
    }

    fun rewriteBorrowed(
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
        context: String = "Protected HEVC slice rewrite",
        nativePlan: NativeProtectedPlan? = null,
        sourceSets: List<ByteArray> = emptyList(),
    ): Borrowed {
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
            "$context: NAL $nalIndex lacks both a complete clear slice header and a safe CABAC splice"
        }
        val opaqueCabacTail = slice.clearCabacBytes < 3
        require(mapped.protectionRanges.isNotEmpty()) {
            "$context: NAL $nalIndex has no detailed clear/encrypted byte map"
        }

        val sourceNalEnd = Math.addExact(mapped.nalOffset, mapped.nalLength)
        require(mapped.nalOffset >= 0 && sourceNalEnd <= sample.size) {
            "$context: NAL $nalIndex is outside the CMAF sample"
        }
        val expectedTailOffset = if (opaqueCabacTail) slice.firstOpaqueCabacByteOffset
            else slice.thirdClearCabacByteOffset
        val rewritten = HevcSliceHeaderRewriter.rewriteInspectedProtectedParts(
            sample, mapped, sourceSps, sourcePps, outputSps, outputPps,
            destinationAddress, loopFilterAcrossSlicesOverride, opaqueCabacTail,
            nativePlan, sourceSets, expectedTailOffset)
        try {
        require(rewritten.sourceTailOffset == expectedTailOffset) {
            "$context: slice rewriter's CABAC splice differs from the protected access-unit inspection"
        }
        val sourceMapResume = rewritten.sourceTailOffset + if (opaqueCabacTail) 0 else 1
        require(rewritten.sourceTailOffset >= 2 &&
            rewritten.sourceTailOffset < mapped.nalLength &&
            sourceMapResume <= mapped.clearNalPrefixBytes) {
            "$context: copied CABAC tail does not begin at a verified header boundary"
        }
        require(isFullyClear(mapped.protectionRanges, 0, sourceMapResume)) {
            "$context: rewritten clear header crosses into encrypted bytes"
        }

        val outputTailOffset = rewritten.size - rewritten.copiedTailBytes
        require(outputTailOffset >= 2 && rewritten.copiedTailBytes > 0 &&
            outputTailOffset + rewritten.copiedTailBytes == rewritten.size) {
            "$context: rewritten NAL tail bounds are inconsistent"
        }
        require(rewritten.copiedTailBytes == mapped.nalLength - rewritten.sourceTailOffset) {
            "$context: rewriter did not preserve the complete source NAL tail"
        }
        // Borrowed.copyInto verifies the byte-exact opaque tail in its final
        // destination; no intermediate full NAL allocation is needed.
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

        // For the three-CABAC-byte path, the copied third byte remains clear. For a
        // header-only rewrite, preserve *every* source CABAC byte and its CBCS protection
        // classification, including encrypted bytes immediately after byte_alignment().
        val outputMapResume = outputTailOffset + if (opaqueCabacTail) 0 else 1
        appendRange(0, outputMapResume, isClear = true)
        mapped.protectionRanges.forEach { range ->
            val intersectionStart = maxOf(sourceMapResume, range.offset)
            val intersectionEnd = minOf(mapped.nalLength, range.endExclusive)
            if (intersectionStart < intersectionEnd) {
                val outputOffset = outputMapResume +
                    (intersectionStart - sourceMapResume)
                appendRange(
                    offset = outputOffset,
                    length = intersectionEnd - intersectionStart,
                    isClear = range.isClear
                )
            }
        }
        require(outputCoveredUntil == rewritten.size) {
            "$context: rewritten protection map does not cover the complete NAL"
        }
        require(rebuiltRanges.firstOrNull()?.offset == 0 &&
            rebuiltRanges.lastOrNull()?.endExclusive == rewritten.size) {
            "$context: rewritten protection map has invalid outer bounds"
        }

        val encryptedBytes = rebuiltRanges.asSequence()
            .filterNot { it.isClear }
            .sumOf { it.lengthBytes.toLong() }
        return Borrowed(
            parts = rewritten,
            protectionRanges = rebuiltRanges,
            destinationAddress = rewritten.destinationAddress,
            sourceTailOffset = rewritten.sourceTailOffset,
            outputTailOffset = outputTailOffset,
            copiedTailBytes = rewritten.copiedTailBytes,
            encryptedByteCount = encryptedBytes
        )
        } catch (failure: Throwable) {
            rewritten.close()
            throw failure
        }
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
