package app.apexview.tiled

/**
 * Per-sample HEVC access-unit inspection for subsample-encrypted CMAF.
 *
 * The caller must first validate the init/fragment encryption metadata and supply the exact
 * subsample map for this sample. Only the contiguous clear prefix of each NAL is copied into the
 * HEVC parser. Encrypted bytes are never interpreted as syntax and this class never decrypts,
 * transforms, or retains ciphertext.
 *
 * This is an inspection gate, not a protected sample rewriter. A slice may be structurally
 * inspectable while lacking the three clear CABAC bytes required by the current slice rewriter.
 */
internal object ProtectedHevcAccessUnitInspector {
    data class NalDescriptor(
        val index: Int,
        val nalType: Int,
        val layerId: Int,
        val temporalIdPlus1: Int,
        val nalLength: Int,
        val clearNalPrefixBytes: Int,
        val fullyClear: Boolean
    )

    data class SourceTile(
        val index: Int,
        val column: Int,
        val row: Int,
        val leftCtb: Int,
        val topCtb: Int,
        val widthInCtbs: Int,
        val heightInCtbs: Int,
        val startAddress: Int
    )

    data class SliceRegion(
        val nalIndex: Int,
        val header: HevcSliceHeaderParser.SliceHeader,
        val tile: SourceTile,
        val clearCabacBytes: Int,
        /** NAL-relative EBSP offset of the third clear CABAC byte, or null when unavailable. */
        val thirdClearCabacByteOffset: Int?
    ) {
        val readyForCurrentHeaderRewriter: Boolean
            get() = clearCabacBytes >= REQUIRED_CLEAR_CABAC_BYTES &&
                thirdClearCabacByteOffset != null
    }

    data class Report(
        val sampleSizeBytes: Int,
        val nals: List<NalDescriptor>,
        val slices: List<SliceRegion>,
        val pictureIdentity: HevcPictureIdentityCompatibility.PictureIdentity,
        val pictureOrderCountLsb: Int,
        val isRandomAccessPicture: Boolean,
        val isIdrPicture: Boolean,
        val sourceTileCount: Int,
        val fullFrameSingleSlice: Boolean,
        val independentSliceEligible: Boolean,
        val slicesReadyForCurrentHeaderRewriter: Boolean
    )

    fun inspectLengthPrefixedSample(
        sample: ByteArray,
        layout: ProtectedHevcSampleLayout.Report,
        sps: HevcParameterSets.SpsMetadata,
        pps: HevcParameterSets.PpsMetadata,
        vps: HevcParameterSets.VpsMetadata? = null,
        context: String = "Protected HEVC access unit"
    ): Report {
        ensure(sample.isNotEmpty(), context, "CMAF sample is empty")
        ensure(sample.size == layout.sampleSizeBytes, context,
            "Subsample layout size does not match the supplied CMAF sample")
        ensure(layout.nals.isNotEmpty(), context, "Subsample layout contains no NAL units")
        ensure(layout.nalLengthSize in 1..4, context, "Unsupported NAL length size")
        ensure(pps.spsId == sps.spsId, context, "PPS references a different SPS")
        ensure(!sps.spsExtensionPresent && !pps.extensionPresent, context,
            "Parameter-set extensions are outside the supported Tizen stitching profile")

        val descriptors = ArrayList<NalDescriptor>(layout.nals.size)
        val slices = mutableListOf<SliceRegion>()
        val seenTiles = mutableSetOf<Int>()
        var firstSliceCount = 0
        var previousAddress = -1
        var expectedNextOffset = 0
        var referenceIdentity: HevcPictureIdentityCompatibility.PictureIdentity? = null

        layout.nals.forEach { mapped ->
            ensure(mapped.index == descriptors.size, context,
                "Subsample layout NAL indices are not contiguous")
            ensure(mapped.lengthFieldOffset == expectedNextOffset, context,
                "Subsample layout NAL boundaries are not contiguous")
            ensure(mapped.lengthFieldOffset >= 0 &&
                mapped.nalOffset == mapped.lengthFieldOffset + layout.nalLengthSize &&
                mapped.nalLength >= 2 &&
                mapped.nalOffset.toLong() + mapped.nalLength <= sample.size.toLong(), context,
                "Subsample layout contains an invalid NAL boundary")
            ensure(mapped.clearNalPrefixBytes in 2..mapped.nalLength, context,
                "Subsample layout does not expose a complete clear NAL header")

            val lengthFromSample = readNalLength(
                sample, mapped.lengthFieldOffset, layout.nalLengthSize, context
            )
            ensure(lengthFromSample == mapped.nalLength, context,
                "Subsample layout NAL length differs from the sample length prefix")
            val prefixEnd = Math.addExact(mapped.nalOffset, mapped.clearNalPrefixBytes)
            // Copy only bytes proven clear by ProtectedHevcSampleLayout. Do not pass the original
            // sample or any byte beyond this prefix to a HEVC syntax parser.
            val clearPrefix = sample.copyOfRange(mapped.nalOffset, prefixEnd)
            val nalHeader = try {
                HevcBitstream.parseNalHeader(clearPrefix)
            } catch (error: Exception) {
                fail(context, "NAL " + mapped.index + " has an invalid clear HEVC header: " +
                    (error.message ?: error.javaClass.simpleName))
            }
            ensure(nalHeader.type == mapped.nalType &&
                nalHeader.layerId == mapped.layerId &&
                nalHeader.temporalIdPlus1 == mapped.temporalIdPlus1, context,
                "Subsample layout NAL header metadata does not match the clear sample bytes")
            ensure(nalHeader.layerId == 0, context,
                "NAL " + mapped.index + " uses a non-zero HEVC layer ID")
            ensure(nalHeader.temporalIdPlus1 <= sps.maxSubLayers, context,
                "NAL " + mapped.index + " temporal_id_plus1 exceeds the SPS sub-layer limit")

            descriptors += NalDescriptor(
                index = mapped.index,
                nalType = nalHeader.type,
                layerId = nalHeader.layerId,
                temporalIdPlus1 = nalHeader.temporalIdPlus1,
                nalLength = mapped.nalLength,
                clearNalPrefixBytes = mapped.clearNalPrefixBytes,
                fullyClear = mapped.clearNalPrefixBytes == mapped.nalLength
            )

            when (nalHeader.type) {
                32 -> {
                    ensure(mapped.clearNalPrefixBytes == mapped.nalLength, context,
                        "In-band VPS is not fully clear and cannot be validated")
                    ensure(vps != null, context, "In-band VPS has no matching hvcC VPS")
                    ensure(HevcParameterSets.parseVps(clearPrefix) == vps, context,
                        "In-band VPS differs from hvcC")
                }
                33 -> {
                    ensure(mapped.clearNalPrefixBytes == mapped.nalLength, context,
                        "In-band SPS is not fully clear and cannot be validated")
                    ensure(HevcParameterSets.parseSps(clearPrefix) == sps, context,
                        "In-band SPS differs from hvcC")
                }
                34 -> {
                    ensure(mapped.clearNalPrefixBytes == mapped.nalLength, context,
                        "In-band PPS is not fully clear and cannot be validated")
                    ensure(HevcParameterSets.parsePps(clearPrefix, sps) == pps, context,
                        "In-band PPS differs from hvcC")
                }
                in 0..31 -> {
                    val slice = try {
                        HevcSliceHeaderParser.parse(
                            clearPrefix, sps, pps, clearNalBytes = clearPrefix.size
                        )
                    } catch (error: Exception) {
                        fail(context, "NAL " + mapped.index + " clear slice header is incomplete or unsupported: " +
                            (error.message ?: error.javaClass.simpleName))
                    }
                    ensure(!slice.dependentSliceSegment, context,
                        "Dependent slice segments cannot be selected as independent source tiles")
                    ensure(slice.entryPointOffsets.isEmpty(), context,
                        "Slice spans multiple tiles or has unsupported entry-point offsets")

                    if (slice.firstSliceSegmentInPicture) {
                        firstSliceCount++
                        ensure(slices.isEmpty(), context,
                            "One CMAF sample contains more than one coded picture")
                        ensure(slice.sliceSegmentAddress == 0, context,
                            "First slice does not start at CTB address zero")
                    } else {
                        ensure(slices.isNotEmpty(), context,
                            "Continuation slice appears before the first slice in the CMAF sample")
                    }
                    ensure(slice.sliceSegmentAddress > previousAddress, context,
                        "Slice CTB addresses are not in increasing raster order")
                    previousAddress = slice.sliceSegmentAddress

                    val pocLsb = slice.picOrderCntLsb
                        ?: if (slice.nalUnitType in 19..20) 0 else null
                    ensure(pocLsb != null, context,
                        "Independent non-IDR slice has no picture-order-count LSB")
                    val identity = HevcPictureIdentityCompatibility.identify(
                        slice, requireNotNull(pocLsb).toLong()
                    )
                    if (referenceIdentity == null) {
                        referenceIdentity = identity
                    } else {
                        val comparison = HevcPictureIdentityCompatibility.compare(
                            listOf(requireNotNull(referenceIdentity)), listOf(identity)
                        )
                        ensure(comparison.compatible, context,
                            "Slice does not belong to the same coded picture: " +
                                comparison.reasons.joinToString("; "))
                    }

                    val tile = tileForAddress(sps, pps, slice.sliceSegmentAddress, context)
                    ensure(tile.startAddress == slice.sliceSegmentAddress, context,
                        "Slice at CTB address " + slice.sliceSegmentAddress +
                            " does not start at a complete source tile boundary")
                    ensure(seenTiles.add(tile.index), context,
                        "More than one independent slice starts in source tile " + tile.index)

                    val headerRbspBytes = slice.bitsConsumed / 8
                    ensure(slice.bitsConsumed % 8 == 0, context,
                        "Parsed slice header is not byte aligned")
                    val rbspOffsets = clearRbspByteOffsets(clearPrefix, context, mapped.index)
                    val cabacBytes = (rbspOffsets.size - headerRbspBytes).coerceAtLeast(0)
                    slices += SliceRegion(
                        nalIndex = mapped.index,
                        header = slice,
                        tile = tile,
                        clearCabacBytes = cabacBytes,
                        thirdClearCabacByteOffset = rbspOffsets.getOrNull(
                            headerRbspBytes + REQUIRED_CLEAR_CABAC_BYTES - 1
                        )
                    )
                }
                in 35..40 -> Unit // AUD, EOS, EOB, filler and SEI: only the clear NAL header is inspected.
                else -> fail(context, "Unsupported or reserved HEVC NAL unit type " + nalHeader.type)
            }
            expectedNextOffset = Math.addExact(mapped.nalOffset, mapped.nalLength)
        }

        ensure(expectedNextOffset == sample.size, context,
            "Subsample layout does not cover the sample's NAL sequence")
        ensure(slices.isNotEmpty(), context, "CMAF sample contains no VCL slices")
        ensure(firstSliceCount == 1, context,
            "CMAF sample must contain exactly one first slice for exactly one picture")

        val identity = requireNotNull(referenceIdentity)
        val columns = requireNotNull(pps.tileColumnWidthsInCtbs)
        val rows = requireNotNull(pps.tileRowHeightsInCtbs)
        val tileCount = Math.multiplyExact(columns.size, rows.size)
        val firstSlice = slices.first().header
        val idr = firstSlice.nalUnitType == 19 || firstSlice.nalUnitType == 20
        val pocLsb = firstSlice.picOrderCntLsb ?: 0

        return Report(
            sampleSizeBytes = sample.size,
            nals = descriptors,
            slices = slices,
            pictureIdentity = identity,
            pictureOrderCountLsb = pocLsb,
            isRandomAccessPicture = firstSlice.nalUnitType in 16..21,
            isIdrPicture = idr,
            sourceTileCount = tileCount,
            fullFrameSingleSlice = tileCount == 1 && slices.size == 1 &&
                firstSlice.sliceSegmentAddress == 0,
            independentSliceEligible = true,
            slicesReadyForCurrentHeaderRewriter = slices.all { it.readyForCurrentHeaderRewriter }
        )
    }

    private fun tileForAddress(
        sps: HevcParameterSets.SpsMetadata,
        pps: HevcParameterSets.PpsMetadata,
        address: Int,
        context: String
    ): SourceTile {
        val columns = requireNotNull(pps.tileColumnWidthsInCtbs)
        val rows = requireNotNull(pps.tileRowHeightsInCtbs)
        ensure(columns.size == pps.numTileColumns && rows.size == pps.numTileRows, context,
            "PPS tile geometry does not match declared tile counts")
        ensure(columns.isNotEmpty() && rows.isNotEmpty(), context, "PPS tile geometry is empty")
        if (!pps.tilesEnabled) {
            ensure(columns.size == 1 && rows.size == 1, context,
                "PPS without tiles declares multi-region tile geometry")
        }

        val pictureCtbCount = Math.multiplyExact(sps.picWidthInCtbs, sps.picHeightInCtbs)
        ensure(address in 0 until pictureCtbCount, context,
            "Slice address exceeds source picture CTB count")
        val rasterRow = address / sps.picWidthInCtbs
        val rasterColumn = address % sps.picWidthInCtbs

        var column = 0
        var left = 0
        while (column < columns.size && rasterColumn >= left + columns[column]) {
            left = Math.addExact(left, columns[column])
            column++
        }
        ensure(column < columns.size, context, "Slice address is outside PPS tile columns")

        var row = 0
        var top = 0
        while (row < rows.size && rasterRow >= top + rows[row]) {
            top = Math.addExact(top, rows[row])
            row++
        }
        ensure(row < rows.size, context, "Slice address is outside PPS tile rows")

        val start = Math.addExact(Math.multiplyExact(top, sps.picWidthInCtbs), left)
        return SourceTile(
            index = Math.addExact(Math.multiplyExact(row, columns.size), column),
            column = column,
            row = row,
            leftCtb = left,
            topCtb = top,
            widthInCtbs = columns[column],
            heightInCtbs = rows[row],
            startAddress = start
        )
    }

    /**
     * Maps clear RBSP byte indices to NAL-relative EBSP offsets. The resulting indices are derived
     * only from the contiguous clear prefix. A dangling emulation-prevention byte at the clear
     * boundary is rejected instead of consulting a potentially encrypted next byte.
     */
    private fun clearRbspByteOffsets(
        clearNalPrefix: ByteArray,
        context: String,
        nalIndex: Int
    ): IntArray {
        val offsets = ArrayList<Int>(clearNalPrefix.size - 2)
        var zeroCount = 0
        var index = 2
        while (index < clearNalPrefix.size) {
            val value = clearNalPrefix[index].toInt() and 0xff
            if (zeroCount >= 2 && value == 3) {
                ensure(index + 1 < clearNalPrefix.size, context,
                    "NAL " + nalIndex + " clear prefix ends inside an emulation-prevention sequence")
                val next = clearNalPrefix[index + 1].toInt() and 0xff
                ensure(next <= 3, context,
                    "NAL " + nalIndex + " contains an invalid clear emulation-prevention sequence")
                zeroCount = 0
                index++
                continue
            }
            offsets += index
            zeroCount = if (value == 0) zeroCount + 1 else 0
            index++
        }
        return offsets.toIntArray()
    }

    private fun readNalLength(
        sample: ByteArray,
        offset: Int,
        lengthSize: Int,
        context: String
    ): Int {
        ensure(offset >= 0 && offset.toLong() + lengthSize <= sample.size.toLong(), context,
            "Truncated NAL length field")
        var value = 0L
        repeat(lengthSize) { index ->
            value = (value shl 8) or (sample[offset + index].toLong() and 0xffL)
        }
        ensure(value <= Int.MAX_VALUE.toLong(), context, "NAL length exceeds supported range")
        return value.toInt()
    }

    private fun ensure(condition: Boolean, context: String, reason: String) {
        if (!condition) fail(context, reason)
    }

    private fun fail(context: String, reason: String): Nothing {
        throw IllegalArgumentException(if (context.isBlank()) reason else context + ": " + reason)
    }

    private const val REQUIRED_CLEAR_CABAC_BYTES = 3
}
