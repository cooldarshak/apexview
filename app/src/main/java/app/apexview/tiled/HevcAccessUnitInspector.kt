package app.apexview.tiled

/**
 * Per-CMAF-sample HEVC access-unit inspection, following F1 Tizen's feedFrame() rules.
 *
 * This is an inspection gate, not a decoder or sample rewriter. It accepts clear, length-prefixed
 * samples and verifies that one sample contains one coded picture, that every VCL NAL is an
 * independent slice, and that each slice starts at a source tile boundary. Picture identity uses
 * the same parsed fields as the Tizen grid composer.
 *
 * The CMAF layer must reject protected samples before this inspector is called.
 */
internal object HevcAccessUnitInspector {
    /** Bounded range into the caller's CMAF sample. Never retain past its ownership lifetime. */
    data class NalRange(val offset: Int, val length: Int)

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
        val tile: SourceTile
    )

    data class Report(
        val nalUnits: List<ByteArray>,
        val nalUnitRanges: List<NalRange>,
        val nalUnitTypes: List<Int>,
        val slices: List<SliceRegion>,
        val pictureIdentity: HevcPictureIdentityCompatibility.PictureIdentity,
        val pictureOrderCountLsb: Int,
        val isRandomAccessPicture: Boolean,
        val isIdrPicture: Boolean,
        val sourceTileCount: Int,
        val fullFrameSingleSlice: Boolean,
        val independentSliceEligible: Boolean
    )

    fun inspectLengthPrefixedSample(
        sample: ByteArray,
        nalLengthSize: Int,
        sps: HevcParameterSets.SpsMetadata,
        pps: HevcParameterSets.PpsMetadata,
        vps: HevcParameterSets.VpsMetadata? = null,
        context: String = "HEVC access unit",
        sampleOffset: Int = 0,
        sampleLength: Int = sample.size,
        retainNalUnits: Boolean = true
    ): Report {
        ensure(sampleOffset >= 0 && sampleLength >= 0 && sampleOffset <= sample.size &&
            sampleLength <= sample.size - sampleOffset, context, "CMAF sample range is out of bounds")
        ensure(sampleLength > 0, context, "CMAF sample is empty")
        ensure(sampleLength <= MAX_SAMPLE_BYTES, context, "CMAF sample exceeds inspection limit")
        ensure(nalLengthSize in 1..4, context, "Unsupported HEVC NAL length size")
        ensure(pps.spsId == sps.spsId, context, "PPS references a different SPS")
        ensure(!sps.spsExtensionPresent && !pps.extensionPresent, context,
            "Parameter-set extensions are outside the supported Tizen stitching profile")

        val nals = splitLengthPrefixed(sample, sampleOffset, sampleLength, nalLengthSize, context)
        val nalTypes = ArrayList<Int>(nals.size)
        val slices = mutableListOf<SliceRegion>()
        val seenTiles = mutableSetOf<Int>()
        var firstSliceCount = 0
        var previousAddress = -1
        var referenceIdentity: HevcPictureIdentityCompatibility.PictureIdentity? = null

        nals.forEachIndexed { nalIndex, nal ->
            val nalHeader = try {
                HevcBitstream.readNalHeader(sample, nal.offset, nal.length)
            } catch (error: Exception) {
                fail(context, "NAL " + nalIndex + " has an invalid HEVC header: " +
                    (error.message ?: error.javaClass.simpleName))
            }
            nalTypes += nalHeader.type
            ensure(nalHeader.layerId == 0, context, "NAL " + nalIndex + " uses a non-zero HEVC layer ID")
            ensure(nalHeader.temporalIdPlus1 <= sps.maxSubLayers, context,
                "NAL " + nalIndex + " temporal_id_plus1 exceeds the SPS sub-layer limit")

            when (nalHeader.type) {
                32 -> {
                    ensure(vps != null, context, "In-band VPS has no matching hvcC VPS")
                    ensure(HevcParameterSets.parseVps(sample.copyOfRange(nal.offset, nal.offset + nal.length)) == vps, context,
                        "In-band VPS differs from hvcC")
                }
                33 -> ensure(HevcParameterSets.parseSps(sample.copyOfRange(nal.offset, nal.offset + nal.length)) == sps, context,
                    "In-band SPS differs from hvcC")
                34 -> ensure(HevcParameterSets.parsePps(sample.copyOfRange(nal.offset, nal.offset + nal.length), sps) == pps, context,
                    "In-band PPS differs from hvcC")
                in 0..31 -> {
                    val slice = try {
                        HevcSliceHeaderParser.parseRange(sample, nal.offset, nal.length, sps, pps)
                    } catch (error: Exception) {
                        fail(context, "NAL " + nalIndex + " slice header is unsupported: " +
                            (error.message ?: error.javaClass.simpleName))
                    }
                    ensure(!slice.dependentSliceSegment, context,
                        "Dependent slice segments cannot be selected as independent source tiles")
                    ensure(slice.entryPointOffsets.isEmpty(), context,
                        "Slice spans multiple tiles or has unsupported entry-point offsets")

                    if (slice.firstSliceSegmentInPicture) {
                        firstSliceCount++
                        ensure(slices.isEmpty(), context, "One CMAF sample contains more than one coded picture")
                        ensure(slice.sliceSegmentAddress == 0, context,
                            "First slice does not start at CTB address zero")
                    } else {
                        ensure(slices.isNotEmpty(), context,
                            "Continuation slice appears before the first slice in the CMAF sample")
                    }
                    ensure(slice.sliceSegmentAddress > previousAddress, context,
                        "Slice CTB addresses are not in increasing raster order")
                    previousAddress = slice.sliceSegmentAddress

                    val pocLsb = slice.picOrderCntLsb ?: if (slice.nalUnitType in 19..20) 0 else null
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
                    slices += SliceRegion(nalIndex, slice, tile)
                }
                in 35..40 -> Unit // AUD, EOS, EOB, filler, prefix/suffix SEI
                else -> fail(context, "Unsupported or reserved HEVC NAL unit type " + nalHeader.type)
            }
        }

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
            // Legacy inspector callers still receive independent, owned NAL bytes.
            // The CMAF assembler uses only ranges, avoiding a full slice copy per frame.
            nalUnits = if (retainNalUnits) nals.map {
                sample.copyOfRange(it.offset, it.offset + it.length)
            } else emptyList(),
            nalUnitRanges = nals,
            nalUnitTypes = nalTypes,
            slices = slices,
            pictureIdentity = identity,
            pictureOrderCountLsb = pocLsb,
            isRandomAccessPicture = firstSlice.nalUnitType in 16..21,
            isIdrPicture = idr,
            sourceTileCount = tileCount,
            fullFrameSingleSlice = tileCount == 1 && slices.size == 1 &&
                firstSlice.sliceSegmentAddress == 0,
            independentSliceEligible = true
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

    private fun splitLengthPrefixed(sample: ByteArray, start: Int, size: Int, lengthSize: Int, context: String): List<NalRange> {
        val result = mutableListOf<NalRange>()
        var offset = start
        val endOfSample = start + size
        while (offset < endOfSample) {
            ensure(endOfSample - offset >= lengthSize, context, "Truncated NAL length prefix")
            var length = 0L
            repeat(lengthSize) {
                length = (length shl 8) or (sample[offset++].toLong() and 0xffL)
            }
            ensure(length >= 2L && length <= (endOfSample - offset).toLong(), context,
                "Invalid NAL length in CMAF sample")
            val end = Math.addExact(offset, length.toInt())
            result += NalRange(offset, length.toInt())
            ensure(result.size <= MAX_NAL_UNITS, context, "CMAF sample contains too many NAL units")
            offset = end
        }
        ensure(offset == endOfSample && result.isNotEmpty(), context,
            "CMAF sample has no complete length-prefixed NAL units")
        return result
    }

    private fun ensure(condition: Boolean, context: String, reason: String) {
        if (!condition) fail(context, reason)
    }

    private fun fail(context: String, reason: String): Nothing {
        throw IllegalArgumentException(if (context.isBlank()) reason else context + ": " + reason)
    }

    private const val MAX_SAMPLE_BYTES = 64 * 1024 * 1024
    private const val MAX_NAL_UNITS = 4096
}
