package app.apexview.tiled

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Clear CMAF-to-CMAF port of the F1 Tizen prepare()/gridSegment() flow.
 *
 * The implementation deliberately reuses HevcTizenGridComposer for parameter-set and slice
 * rewriting, HevcFragmentSequenceCompatibility for cross-feed timing/POC checks, and the
 * existing clear CMAF writers for output. It does not parse, copy, or emit encrypted samples.
 *
 * Current supported container profile is intentionally narrow: one video track, one hvc1/hev1
 * sample description, one moof/traf/trun/mdat per media fragment, default-base-is-moof, and
 * explicit per-sample duration/size/flags/composition offset. Unsupported layouts fail closed.
 */
internal object HevcClearCmafGridComposer {
    data class Feed(
        val initializationSegment: ByteArray,
        val mediaFragments: List<ByteArray>,
        val label: String
    )

    data class Request(
        val feeds: List<Feed>,
        val outputGrid: HevcOutputGridDimensions,
        val cells: List<HevcTizenGridComposer.CellSelection>,
        val outputTrackId: Long = 1,
        val firstOutputSequenceNumber: Long = 1,
        val frameRateNumerator: Int = 50,
        val frameRateDenominator: Int = 1
    )

    data class Output(
        val initializationSegment: ByteArray,
        val mediaFragments: List<ByteArray>,
        val codedWidth: Int,
        val codedHeight: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val sampleCount: Int,
        val fragmentCount: Int,
        val evidence: List<HevcTizenGridComposer.SliceEvidence>
    )

    private data class SourceSample(
        val bytes: ByteArray,
        val duration: Long,
        val compositionOffset: Long,
        val flags: Long
    )

    private data class SourceFragment(
        val baseDecodeTime: Long,
        val samples: List<SourceSample>
    )

    private data class ParsedFeed(
        val track: CmafBoxInspector.TrackMetadata,
        val config: CmafBoxInspector.HevcConfiguration,
        val parameterSets: List<ByteArray>,
        val fragments: List<SourceFragment>,
        val inspectorFragments: List<CmafBoxInspector.FragmentMetadata>,
        val annexB: ByteArray
    )

    fun compose(request: Request): Output {
        require(request.feeds.size >= 2) { "At least two clear CMAF feeds are required" }
        require(request.feeds.all { it.mediaFragments.isNotEmpty() }) {
            "Every source feed must contain at least one media fragment"
        }
        require(request.outputTrackId in 1 until UINT32_MAX) { "Output track ID is invalid" }
        require(request.firstOutputSequenceNumber in 1..UINT32_MAX) {
            "Output fragment sequence number is invalid"
        }

        val parsedFeeds = request.feeds.mapIndexed { index, feed -> parseFeed(feed, index) }
        val first = parsedFeeds.first()
        val composed = HevcTizenGridComposer.composeAnnexBStreams(
            feedStreams = parsedFeeds.map { it.annexB },
            grid = request.outputGrid,
            cells = request.cells,
            frameRateNumerator = request.frameRateNumerator,
            frameRateDenominator = request.frameRateDenominator,
            outputNalLengthSize = first.config.nalLengthSize
        )
        require(composed.pictures.size == first.fragments.sumOf { it.samples.size }) {
            "Composed picture count does not match the CMAF sample sequence"
        }
        require(composed.sourcePictureIdentities.size == parsedFeeds.size) {
            "Composer did not return source picture identity for every feed"
        }
        parsedFeeds.drop(1).forEachIndexed { offset, candidate ->
            require(candidate.fragments.size == first.fragments.size) {
                "Feed ${offset + 1} fragment count differs from feed 0"
            }
            val compatibility = HevcFragmentSequenceCompatibility.compare(
                sequence(request.feeds[0].label, first, composed.sourcePictureIdentities[0]),
                sequence(request.feeds[offset + 1].label, candidate, composed.sourcePictureIdentities[offset + 1])
            )
            require(compatibility.compatible) {
                "CMAF feed timing/picture compatibility failed: ${compatibility.reasons.joinToString("; ")}"
            }
        }

        val init = HevcClearCmafInitSegmentWriter.write(
            HevcClearCmafInitSegmentWriter.Request(
                trackId = request.outputTrackId,
                mediaTimescale = requireNotNull(first.track.timescale),
                parameterSets = composed.outputParameters
            )
        )

        val outputFragments = mutableListOf<ByteArray>()
        var pictureOffset = 0
        parsedFeeds.first().fragments.forEachIndexed { fragmentIndex, sourceFragment ->
            val count = sourceFragment.samples.size
            val outputSamples = sourceFragment.samples.mapIndexed { sampleIndex, sourceSample ->
                val picture = composed.pictures[pictureOffset + sampleIndex]
                HevcClearCmafFragmentWriter.Sample(
                    data = picture.sampleData,
                    durationTicks = sourceSample.duration,
                    compositionTimeOffsetTicks = sourceSample.compositionOffset,
                    sampleFlags = sourceSample.flags
                )
            }
            outputFragments += HevcClearCmafFragmentWriter.write(
                trackId = request.outputTrackId,
                sequenceNumber = Math.addExact(request.firstOutputSequenceNumber, fragmentIndex.toLong()),
                baseDecodeTimeTicks = sourceFragment.baseDecodeTime,
                samples = outputSamples,
                sourceTrackEncrypted = false,
                sourceFragmentHasEncryptionMetadata = false
            )
            pictureOffset += count
        }

        return Output(
            initializationSegment = init,
            mediaFragments = outputFragments,
            codedWidth = composed.codedWidth,
            codedHeight = composed.codedHeight,
            displayWidth = composed.displayWidth,
            displayHeight = composed.displayHeight,
            sampleCount = composed.pictures.size,
            fragmentCount = outputFragments.size,
            evidence = composed.pictures.flatMap { it.evidence }
        )
    }

    private fun sequence(
        label: String,
        feed: ParsedFeed,
        pictures: List<HevcPictureIdentityCompatibility.PictureIdentity>
    ) = HevcFragmentSequenceCompatibility.FeedSequence(
        label = label,
        track = feed.track,
        fragments = feed.inspectorFragments,
        pictures = pictures
    )

    private fun parseFeed(feed: Feed, feedIndex: Int): ParsedFeed {
        val initReport = CmafBoxInspector.inspect(feed.initializationSegment)
        require(initReport.errors.isEmpty()) {
            "Feed ${feed.label} init segment is invalid: ${initReport.errors.joinToString("; ")}"
        }
        require(initReport.tracks.size == 1) {
            "Feed ${feed.label} must contain exactly one track"
        }
        val track = initReport.tracks.single()
        require(track.handler == "vide" && track.sampleEntry in setOf("hvc1", "hev1")) {
            "Feed ${feed.label} is not a supported HEVC video track"
        }
        require(!track.encrypted && track.protectionScheme == null && track.trackEncryption == null &&
            !track.sampleEncryptionBoxPresent && !track.sampleAuxiliarySizesPresent &&
            !track.sampleAuxiliaryOffsetsPresent && !track.sampleGroupDescriptionPresent &&
            !track.sampleToGroupPresent) {
            "Feed ${feed.label} is protected; clear CMAF composition rejects DRM tracks"
        }
        val config = requireNotNull(track.hevcConfiguration) {
            "Feed ${feed.label} has no parsed hvcC configuration"
        }
        require(config.parameterSetErrors.isEmpty() && config.vps.size == 1 &&
            config.sps.size == 1 && config.pps.size == 1) {
            "Feed ${feed.label} must have one valid VPS/SPS/PPS triplet"
        }
        val rawParameterSets = extractParameterSets(feed.initializationSegment, config.nalLengthSize)
        val fragments = mutableListOf<SourceFragment>()
        val metadata = mutableListOf<CmafBoxInspector.FragmentMetadata>()
        val annexB = ByteArrayOutputStream()
        rawParameterSets.forEach { appendAnnexB(annexB, it) }

        feed.mediaFragments.forEachIndexed { fragmentIndex, bytes ->
            val report = CmafBoxInspector.inspect(bytes, priorInitialization = initReport)
            require(report.errors.isEmpty()) {
                "Feed ${feed.label} fragment $fragmentIndex is invalid: ${report.errors.joinToString("; ")}"
            }
            require(report.fragments.size == 1) {
                "Feed ${feed.label} fragment $fragmentIndex must contain exactly one video traf"
            }
            val fragmentMetadata = report.fragments.single()
            require(fragmentMetadata.trackId == track.trackId &&
                fragmentMetadata.timescale == track.timescale) {
                "Feed ${feed.label} fragment $fragmentIndex track metadata does not match its init segment"
            }
            require(!fragmentMetadata.sampleEncryptionBoxPresent &&
                !fragmentMetadata.sampleAuxiliarySizesPresent &&
                !fragmentMetadata.sampleAuxiliaryOffsetsPresent &&
                !fragmentMetadata.sampleGroupDescriptionPresent &&
                !fragmentMetadata.sampleToGroupPresent &&
                !fragmentMetadata.sampleGroupEncryptionPresent) {
                "Feed ${feed.label} fragment $fragmentIndex contains encryption metadata"
            }
            require(fragmentMetadata.timingComplete &&
                fragmentMetadata.sampleTimings.size == fragmentMetadata.sampleCount) {
                "Feed ${feed.label} fragment $fragmentIndex has incomplete sample timing"
            }
            val parsed = parseFragmentSamples(bytes, fragmentMetadata)
            parsed.samples.forEachIndexed { sampleIndex, sample ->
                val nals = splitLengthPrefixed(sample.bytes, config.nalLengthSize)
                val vcl = nals.filter { HevcBitstream.parseNalHeader(it).type in 0..31 }
                require(vcl.isNotEmpty()) {
                    "Feed ${feed.label} fragment $fragmentIndex sample $sampleIndex contains no VCL NAL"
                }
                require(vcl.count { isFirstSlice(it) } == 1) {
                    "Feed ${feed.label} fragment $fragmentIndex sample $sampleIndex must contain exactly one first slice"
                }
                nals.forEach { appendAnnexB(annexB, it) }
            }
            fragments += parsed
            metadata += fragmentMetadata
        }

        val extractedSampleCount = fragments.sumOf { it.samples.size }
        require(metadata.sumOf { it.sampleCount } == extractedSampleCount) {
            "Feed ${feed.label} CMAF fragment sample counts are inconsistent"
        }
        return ParsedFeed(
            track, config, rawParameterSets, fragments, metadata, annexB.toByteArray()
        )
    }

    private fun parseFragmentSamples(
        bytes: ByteArray,
        metadata: CmafBoxInspector.FragmentMetadata
    ): SourceFragment {
        val top = boxes(bytes, 0, bytes.size)
        val moof = top.singleOrNull { it.type == "moof" }
            ?: error("Expected exactly one moof")
        val mdat = top.singleOrNull { it.type == "mdat" }
            ?: error("Expected exactly one mdat")
        require(top.size == 2 && top[0].type == "moof" && top[1].type == "mdat") {
            "Only a moof followed by one mdat is supported"
        }
        val traf = boxes(bytes, moof.payloadStart, moof.end).singleOrNull { it.type == "traf" }
            ?: error("Expected exactly one traf")
        val children = boxes(bytes, traf.payloadStart, traf.end)
        require(children.none { it.type in ENCRYPTION_BOXES }) {
            "Fragment contains encryption auxiliary metadata"
        }
        val tfhd = children.singleOrNull { it.type == "tfhd" } ?: error("traf has no tfhd")
        val tfdt = children.singleOrNull { it.type == "tfdt" } ?: error("traf has no tfdt")
        val trun = children.singleOrNull { it.type == "trun" } ?: error("traf must contain one trun")
        require(children.count { it.type == "trun" } == 1) { "Multiple trun boxes are not supported" }

        val h = ByteBuffer.wrap(bytes, tfhd.payloadStart, tfhd.end - tfhd.payloadStart).order(ByteOrder.BIG_ENDIAN)
        require(h.remaining() >= 8) { "Truncated tfhd" }
        val hVersion = h.get().toInt() and 0xff
        val hFlags = readU24(h)
        require(hVersion == 0 && (hFlags and 0x020000) != 0 && (hFlags and 0x000001) == 0) {
            "tfhd must use default-base-is-moof without an explicit base_data_offset"
        }
        val trackId = readU32(h)
        require(trackId == metadata.trackId) { "tfhd track ID does not match inspected metadata" }
        if ((hFlags and 0x000002) != 0) {
            require(readU32(h) == 1L) { "Only sample description index 1 is supported" }
        }
        require((hFlags and 0x000008) == 0 && (hFlags and 0x000010) == 0 &&
            (hFlags and 0x000020) == 0) {
            "tfhd default sample values are not supported; trun must carry explicit sample fields"
        }

        val d = ByteBuffer.wrap(bytes, tfdt.payloadStart, tfdt.end - tfdt.payloadStart).order(ByteOrder.BIG_ENDIAN)
        require(d.remaining() >= 8) { "Truncated tfdt" }
        val dVersion = d.get().toInt() and 0xff
        val dFlags = readU24(d)
        require(dFlags == 0 && dVersion in 0..1) { "Unsupported tfdt FullBox header" }
        val baseDecodeTime = if (dVersion == 1) {
            require(d.remaining() >= 8) { "Truncated version-1 tfdt" }
            d.long.also { require(it >= 0) { "Decode time exceeds signed 64-bit range" } }
        } else readU32(d)
        require(!d.hasRemaining()) { "Unexpected trailing tfdt bytes" }
        require(metadata.baseDecodeTimeTicks == baseDecodeTime) {
            "tfdt differs from the independently inspected base decode time"
        }

        val t = ByteBuffer.wrap(bytes, trun.payloadStart, trun.end - trun.payloadStart).order(ByteOrder.BIG_ENDIAN)
        require(t.remaining() >= 12) { "Truncated trun" }
        val version = t.get().toInt() and 0xff
        val flags = readU24(t)
        require(version == 1 && (flags and 0x000001) != 0 &&
            (flags and 0x000100) != 0 && (flags and 0x000200) != 0 &&
            (flags and 0x000400) != 0 && (flags and 0x000800) != 0) {
            "trun must explicitly carry data offset, duration, size, flags and signed composition offset"
        }
        val sampleCount = readU32(t)
        require(sampleCount == metadata.sampleCount.toLong()) { "trun sample count differs from inspected metadata" }
        val dataOffset = t.int.toLong()
        val sampleStart = Math.addExact(moof.start.toLong(), dataOffset)
        require(sampleStart >= mdat.payloadStart.toLong() && sampleStart <= mdat.end.toLong()) {
            "trun data offset does not point into mdat"
        }
        val samples = ArrayList<SourceSample>(metadata.sampleCount)
        var cursor = sampleStart
        repeat(metadata.sampleCount) { index ->
            require(t.remaining() >= 16) { "Truncated trun sample $index" }
            val duration = readU32(t)
            val size = readU32(t)
            val sampleFlags = readU32(t)
            val compositionOffset = t.int.toLong()
            require(duration > 0 && size > 0 && size <= Int.MAX_VALUE) {
                "Invalid duration or size in trun sample $index"
            }
            val end = Math.addExact(cursor, size)
            require(cursor >= mdat.payloadStart && end <= mdat.end.toLong()) {
                "Sample $index extends outside mdat"
            }
            val timing = metadata.sampleTimings[index]
            require(timing.durationTicks == duration &&
                timing.compositionOffsetTicks == compositionOffset &&
                timing.sampleFlags == sampleFlags &&
                timing.sampleSizeBytes == size) {
                "trun sample $index differs from independently inspected timing metadata"
            }
            samples += SourceSample(
                bytes.copyOfRange(cursor.toInt(), end.toInt()),
                duration, compositionOffset, sampleFlags
            )
            cursor = end
        }
        require(!t.hasRemaining()) { "Unexpected trailing trun bytes" }
        require(cursor == mdat.end.toLong()) { "Sample sizes do not exactly cover the mdat payload" }
        return SourceFragment(baseDecodeTime, samples)
    }

    private data class Box(val type: String, val start: Int, val payloadStart: Int, val end: Int)

    private fun boxes(data: ByteArray, start: Int, end: Int): List<Box> {
        require(start >= 0 && end <= data.size && start <= end) { "Invalid ISO-BMFF box range" }
        val result = mutableListOf<Box>()
        var cursor = start
        while (cursor < end) {
            require(end - cursor >= 8) { "Truncated ISO-BMFF box header" }
            val view = ByteBuffer.wrap(data, cursor, end - cursor).order(ByteOrder.BIG_ENDIAN)
            val size32 = readU32(view)
            val typeBytes = ByteArray(4)
            view.get(typeBytes)
            val type = typeBytes.toString(Charsets.US_ASCII)
            val headerSize: Int
            val size: Long
            if (size32 == 1L) {
                require(view.remaining() >= 8) { "Truncated large-size box header" }
                size = view.long
                headerSize = 16
            } else {
                require(size32 != 0L) { "Size-to-end ISO-BMFF boxes are not supported" }
                size = size32
                headerSize = 8
            }
            require(size >= headerSize && size <= (end - cursor).toLong() && size <= Int.MAX_VALUE) {
                "Invalid size for ISO-BMFF box $type"
            }
            val boxEnd = Math.addExact(cursor, size.toInt())
            result += Box(type, cursor, cursor + headerSize, boxEnd)
            cursor = boxEnd
        }
        require(cursor == end) { "ISO-BMFF child boxes do not fill their parent" }
        return result
    }

    private fun extractParameterSets(init: ByteArray, nalLengthSize: Int): List<ByteArray> {
        val top = boxes(init, 0, init.size)
        val moov = top.single { it.type == "moov" }
        val trak = boxes(init, moov.payloadStart, moov.end).single { it.type == "trak" }
        val mdia = boxes(init, trak.payloadStart, trak.end).single { it.type == "mdia" }
        val minf = boxes(init, mdia.payloadStart, mdia.end).single { it.type == "minf" }
        val stbl = boxes(init, minf.payloadStart, minf.end).single { it.type == "stbl" }
        val stsd = boxes(init, stbl.payloadStart, stbl.end).single { it.type == "stsd" }
        val s = ByteBuffer.wrap(init, stsd.payloadStart, stsd.end - stsd.payloadStart).order(ByteOrder.BIG_ENDIAN)
        require(s.remaining() >= 8) { "Truncated stsd" }
        s.position(s.position() + 4)
        require(readU32(s) == 1L) { "Only one sample description is supported" }
        val entryStart = s.position()
        val entrySize = readU32(ByteBuffer.wrap(init, entryStart, stsd.end - entryStart).order(ByteOrder.BIG_ENDIAN))
        require(entrySize >= 86 && entryStart + entrySize <= stsd.end) { "Invalid visual sample entry" }
        val childStart = entryStart + 8 + 78
        val hvcC = boxes(init, childStart, (entryStart + entrySize).toInt()).single { it.type == "hvcC" }
        val p = ByteBuffer.wrap(init, hvcC.payloadStart, hvcC.end - hvcC.payloadStart).order(ByteOrder.BIG_ENDIAN)
        require(p.remaining() >= 23) { "Truncated hvcC" }
        p.position(p.position() + 21)
        val encodedLengthSize = (p.get().toInt() and 0x03) + 1
        require(encodedLengthSize == nalLengthSize) { "hvcC NAL length size changed during parsing" }
        val arrays = p.get().toInt() and 0xff
        val result = mutableListOf<ByteArray>()
        repeat(arrays) {
            require(p.remaining() >= 3) { "Truncated hvcC array" }
            val arrayType = p.get().toInt() and 0x3f
            val count = p.short.toInt() and 0xffff
            repeat(count) {
                require(p.remaining() >= 2) { "Truncated hvcC NAL length" }
                val length = p.short.toInt() and 0xffff
                require(length >= 2 && length <= p.remaining()) { "Invalid hvcC NAL length" }
                val nal = ByteArray(length)
                p.get(nal)
                val type = HevcBitstream.parseNalHeader(nal).type
                require(type == arrayType) { "hvcC array type does not match NAL header" }
                if (type in 32..34) result += nal
            }
        }
        require(!p.hasRemaining()) { "Unexpected trailing hvcC bytes" }
        require(result.map { HevcBitstream.parseNalHeader(it).type }.toSet() == setOf(32, 33, 34)) {
            "hvcC does not contain VPS, SPS and PPS"
        }
        return result
    }

    private fun splitLengthPrefixed(sample: ByteArray, lengthSize: Int): List<ByteArray> {
        require(lengthSize in 1..4) { "Unsupported HEVC NAL length size" }
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < sample.size) {
            require(sample.size - offset >= lengthSize) { "Truncated NAL length prefix" }
            var length = 0L
            repeat(lengthSize) { length = (length shl 8) or (sample[offset++].toLong() and 0xffL) }
            require(length >= 2 && length <= sample.size - offset) { "Invalid NAL length in CMAF sample" }
            val end = Math.addExact(offset, length.toInt())
            result += sample.copyOfRange(offset, end)
            offset = end
        }
        require(offset == sample.size && result.isNotEmpty()) { "CMAF sample contains no complete NAL units" }
        return result
    }

    private fun readSlicePpsId(nal: ByteArray): Int {
        val type = HevcBitstream.parseNalHeader(nal).type
        val bits = HevcBitReader(HevcBitstream.ebspToRbsp(nal.copyOfRange(2, nal.size)))
        bits.readBit()
        if (type in 16..23) bits.readBit()
        val id = bits.readUnsignedExpGolomb()
        require(id <= 63L) { "slice_pic_parameter_set_id exceeds supported range" }
        return id.toInt()
    }

    private fun isFirstSlice(nal: ByteArray): Boolean {
        require(nal.size >= 3) { "Truncated HEVC VCL NAL" }
        return ((nal[2].toInt() ushr 7) and 1) != 0
    }

    private fun appendAnnexB(out: ByteArrayOutputStream, nal: ByteArray) {
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(nal)
    }

    private fun readU24(buffer: ByteBuffer): Int =
        ((buffer.get().toInt() and 0xff) shl 16) or
            ((buffer.get().toInt() and 0xff) shl 8) or
            (buffer.get().toInt() and 0xff)

    private fun readU32(buffer: ByteBuffer): Long =
        buffer.int.toLong() and UINT32_MAX

    private val ENCRYPTION_BOXES = setOf("senc", "saiz", "saio", "sbgp", "sgpd")
    private const val UINT32_MAX = 0xffff_ffffL
}
