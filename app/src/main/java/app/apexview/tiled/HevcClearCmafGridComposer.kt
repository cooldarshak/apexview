package app.apexview.tiled

import java.io.InterruptedIOException
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
 * resolved sample duration/size/flags/composition offset from trun/tfhd/trex. Unsupported layouts fail closed.
 */
internal object HevcClearCmafGridComposer {
    /** Short-lived validated metadata bound to the exact owned media and init report. */
    class InspectedMedia private constructor(
        private val bytes: ByteArray, private val init: CmafBoxInspector.Report,
        private val report: CmafBoxInspector.Report,
    ) {
        fun reportFor(source: ByteArray, initialization: CmafBoxInspector.Report): CmafBoxInspector.Report {
            require(source === bytes && initialization === init) { "CMAF inspection belongs to another fragment" }
            return report
        }
        companion object {
            fun inspect(bytes: ByteArray, init: CmafBoxInspector.Report) =
                InspectedMedia(bytes, init, CmafBoxInspector.inspect(bytes, priorInitialization = init))
        }
    }

    data class Feed(
        val initializationSegment: ByteArray,
        val mediaFragments: List<ByteArray>,
        val label: String,
        /** Validated once against this session's owned initialization bytes, never a different rendition. */
        val validatedInitialization: CmafBoxInspector.Report? = null,
        val inspectedMedia: List<InspectedMedia>? = null,
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
        val evidence: List<HevcTizenGridComposer.SliceEvidence>,
        val parseMs: Long = 0,
        val rewriteMs: Long = 0,
        val packageMs: Long = 0,
        /** CPU time of the composing thread only (not concurrent network/fetch threads). */
        val parseCpuMs: Long = 0,
        val rewriteCpuMs: Long = 0,
        val packageCpuMs: Long = 0,
        val cmafInspectorMs: Long = 0,
        val nalInspectorMs: Long = 0,
        /** Deterministic removed staging bytes, NOT process-wide ART allocation data. */
        val sourceAnnexBBytesAvoided: Long = 0,
        /** Source NAL staging bytes avoided (all passthrough NALs, VCL only for stitching). */
        val sourceNalCopyBytesAvoided: Long = 0,
        val rewrittenTailStagingBytesAvoided: Long = 0,
    )

    private data class SourceSample(
        val bytes: ByteArray,
        val offset: Int,
        val length: Int,
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
        val nalUnits: MutableList<HevcBitstream.NalSpan>,
        val firstVclType: Int?,
        val validatedNalBytes: Long,
        val borrowedVclBytes: Long,
        val annexBEquivalentBytes: Int,
        val cmafInspectorNanos: Long,
        val nalInspectorNanos: Long,
    )

    /** A single clear source needs no stitching. Keep its bytes, after the same protection/syntax checks. */
    fun passThrough(feed: Feed, transferMediaOwnership: Boolean = false): Output {
        val parsed = parseFeed(feed, 0, retainSpans = false)
        try {
            val sps = parsed.config.sps.single()
            require(parsed.firstVclType?.let { it in 16..21 } == true) {
                "A standalone replay segment must begin with an IRAP picture"
            }
            return Output(feed.initializationSegment.copyOf(),
                if (transferMediaOwnership) feed.mediaFragments.toList() else feed.mediaFragments.map { it.copyOf() },
                sps.width, sps.height, sps.displayWidth, sps.displayHeight,
                parsed.fragments.sumOf { it.samples.size }, parsed.fragments.size, emptyList(),
                sourceNalCopyBytesAvoided = parsed.validatedNalBytes)
        } finally {
            releaseNalSpans(parsed)
        }
    }

    fun compose(request: Request): Output {
        require(request.feeds.size >= 2) { "At least two clear CMAF feeds are required" }
        require(request.feeds.all { it.mediaFragments.isNotEmpty() }) {
            "Every source feed must contain at least one media fragment"
        }
        require(request.outputTrackId in 1 until UINT32_MAX) { "Output track ID is invalid" }
        require(request.firstOutputSequenceNumber in 1..UINT32_MAX) {
            "Output fragment sequence number is invalid"
        }

        val started = System.nanoTime()
        val startedCpu = android.os.Debug.threadCpuTimeNanos()
        val parsedFeeds = ArrayList<ParsedFeed>(request.feeds.size)
        try {
            request.feeds.forEachIndexed { index, feed -> parsedFeeds += parseFeed(feed, index) }
        } catch (error: Throwable) {
            parsedFeeds.forEach(::releaseNalSpans)
            throw error
        }
        val parsedAt = System.nanoTime()
        val parsedCpuAt = android.os.Debug.threadCpuTimeNanos()
        val first = parsedFeeds.first()
        val composed = try {
            HevcTizenGridComposer.composeNalSpans(
                feedNals = parsedFeeds.map { it.nalUnits },
                grid = request.outputGrid,
                cells = request.cells,
                frameRateNumerator = request.frameRateNumerator,
                frameRateDenominator = request.frameRateDenominator,
                outputNalLengthSize = first.config.nalLengthSize,
                includeAnnexBOutput = false,
                emitSampleData = false,
                emitNalParts = true
            )
        } finally {
            // The grid composer retains only owned headers and borrowed tail ranges.
            // Drop its original NAL descriptors; the session keeps the source arrays
            // immutable and alive until all final fragments have been packaged.
            parsedFeeds.forEach(::releaseNalSpans)
        }
        val rewrittenAt = System.nanoTime()
        val rewrittenCpuAt = android.os.Debug.threadCpuTimeNanos()
        val outputFragments = mutableListOf<ByteArray>()
        var committed = false
        var init: ByteArray? = null
        try {
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

        init = HevcClearCmafInitSegmentWriter.write(
            HevcClearCmafInitSegmentWriter.Request(
                trackId = request.outputTrackId,
                mediaTimescale = requireNotNull(first.track.timescale),
                parameterSets = composed.outputParameters,
                nalLengthSize = first.config.nalLengthSize
            )
        )

            var pictureOffset = 0
            parsedFeeds.first().fragments.forEachIndexed { fragmentIndex, sourceFragment ->
                checkNotInterrupted()
                val count = sourceFragment.samples.size
                val outputSamples = sourceFragment.samples.mapIndexed { sampleIndex, sourceSample ->
                    val picture = composed.pictures[pictureOffset + sampleIndex]
                    HevcClearCmafFragmentWriter.NalPartsSample(
                        nals = picture.nalParts,
                        durationTicks = sourceSample.duration,
                        compositionTimeOffsetTicks = sourceSample.compositionOffset,
                        sampleFlags = sourceSample.flags
                    )
                }
                outputFragments += HevcClearCmafFragmentWriter.writeNalParts(
                    trackId = request.outputTrackId,
                    sequenceNumber = Math.addExact(request.firstOutputSequenceNumber, fragmentIndex.toLong()),
                    baseDecodeTimeTicks = sourceFragment.baseDecodeTime,
                    samples = outputSamples,
                    nalLengthSize = first.config.nalLengthSize,
                    sourceTrackEncrypted = false,
                    sourceFragmentHasEncryptionMetadata = false
                )
                pictureOffset += count
            }

            val output = Output(
                initializationSegment = requireNotNull(init),
                mediaFragments = outputFragments,
                codedWidth = composed.codedWidth,
                codedHeight = composed.codedHeight,
                displayWidth = composed.displayWidth,
                displayHeight = composed.displayHeight,
                sampleCount = composed.pictures.size,
                fragmentCount = outputFragments.size,
                evidence = composed.pictures.flatMap { it.evidence },
                parseMs = (parsedAt - started) / 1_000_000L,
                rewriteMs = (rewrittenAt - parsedAt) / 1_000_000L,
                packageMs = (System.nanoTime() - rewrittenAt) / 1_000_000L,
                parseCpuMs = ((parsedCpuAt - startedCpu) / 1_000_000L).coerceAtLeast(0L),
                rewriteCpuMs = ((rewrittenCpuAt - parsedCpuAt) / 1_000_000L).coerceAtLeast(0L),
                packageCpuMs = ((android.os.Debug.threadCpuTimeNanos() - rewrittenCpuAt) / 1_000_000L).coerceAtLeast(0L),
                // Aggregate nanoseconds *before* converting to milliseconds.
                // Flooring each of eight feeds separately hid sub-ms inspector
                // work and falsely reported 0 for a nonzero total.
                cmafInspectorMs = parsedFeeds.sumOf { it.cmafInspectorNanos } / 1_000_000L,
                nalInspectorMs = parsedFeeds.sumOf { it.nalInspectorNanos } / 1_000_000L,
                sourceAnnexBBytesAvoided = parsedFeeds.sumOf { it.annexBEquivalentBytes.toLong() },
                sourceNalCopyBytesAvoided = parsedFeeds.sumOf { it.borrowedVclBytes },
                rewrittenTailStagingBytesAvoided = composed.pictures.sumOf { picture ->
                    picture.nalParts.sumOf { it.copiedTailBytes.toLong() }
                },
            )
            committed = true
            return output
        } finally {
            // A failed/cancelled packaging pass may have already written valid CMAF
            // fragments. They are not visible to a caller and must not keep copied
            // clear HEVC payloads alive. Successful outputs transfer ownership.
            if (!committed) {
                init?.fill(0)
                outputFragments.forEach { it.fill(0) }
            }
            // Cover *all* validation failures after rewriting, not only errors
            // in packaging. The final CMAF fragments own their copied bytes; no
            // full rewritten HEVC sample buffers survive successful packaging.
            composed.pictures.forEach { picture ->
                picture.vclNals.forEach { it.fill(0) }
                picture.nalParts.forEach { it.close() }
            }
        }
    }

    private fun releaseNalSpans(feed: ParsedFeed) {
        feed.nalUnits.clear() // Borrowed source bytes must never be zeroed here.
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

    private fun parseFeed(feed: Feed, feedIndex: Int, retainSpans: Boolean = true): ParsedFeed {
        val initialInspectStarted = System.nanoTime()
        val initReport = feed.validatedInitialization ?: CmafBoxInspector.inspect(feed.initializationSegment)
        var cmafInspectorNanos = System.nanoTime() - initialInspectStarted
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
        val rawParameterSets = if (feed.validatedInitialization != null &&
            config.rawVpsNals.size == 1 && config.rawSpsNals.size == 1 && config.rawPpsNals.size == 1) {
            config.rawVpsNals + config.rawSpsNals + config.rawPpsNals
        } else extractParameterSets(feed.initializationSegment, config.nalLengthSize)
        val fragments = mutableListOf<SourceFragment>()
        val metadata = mutableListOf<CmafBoxInspector.FragmentMetadata>()
        require(feed.inspectedMedia == null || feed.inspectedMedia.size == feed.mediaFragments.size)
        feed.mediaFragments.forEachIndexed { fragmentIndex, bytes ->
            checkNotInterrupted()
            val fragmentInspectStarted = System.nanoTime()
            val report = feed.inspectedMedia?.get(fragmentIndex)?.reportFor(bytes, initReport)
                ?: CmafBoxInspector.inspect(bytes, priorInitialization = initReport)
            cmafInspectorNanos += System.nanoTime() - fragmentInspectStarted
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
            fragments += parsed
            metadata += fragmentMetadata
        }

        // Keep the existing overflow limit, without allocating the whole intermediate stream.
        val nalInspectStarted = System.nanoTime()
        val annexBEquivalentBytes = exactAnnexBSize(rawParameterSets, fragments, config.nalLengthSize)
        val nalUnits = ArrayList<HevcBitstream.NalSpan>()
        var firstVclType: Int? = null
        var validatedNalBytes = 0L
        var borrowedVclBytes = 0L
        var validatedAnnexBBytes = 0L
        try {
            fun addNal(bytes: ByteArray, offset: Int, length: Int) {
                require(offset >= 0 && length >= 2 && offset <= bytes.size &&
                    length <= bytes.size - offset) { "Invalid HEVC NAL range" }
                // Match the legacy Annex-B splitter's terminal-zero handling.
                var end = offset + length
                while (end > offset && bytes[end - 1] == 0.toByte()) end--
                require(end - offset >= 2) { "Truncated HEVC NAL" }
                // Both passthrough and grid composition inspect immutable input
                // ranges. Only rewritten output NALs are materialized as owned arrays.
                val nalLength = end - offset
                val header = HevcBitstream.readNalHeader(bytes, offset, nalLength)
                if (firstVclType == null && header.type in 0..31) firstVclType = header.type
                validatedNalBytes = Math.addExact(validatedNalBytes, nalLength.toLong())
                validatedAnnexBBytes = Math.addExact(validatedAnnexBBytes, nalLength.toLong() + 4L)
                if (retainSpans) {
                    nalUnits += HevcBitstream.NalSpan(bytes, offset, nalLength)
                    if (header.type in 0..31)
                        borrowedVclBytes = Math.addExact(borrowedVclBytes, nalLength.toLong())
                }
            }
            rawParameterSets.forEach { addNal(it, 0, it.size) }
            fragments.forEachIndexed { fragmentIndex, parsed ->
                parsed.samples.forEachIndexed { sampleIndex, sample ->
                    checkNotInterrupted()
                    val accessUnit = HevcAccessUnitInspector.inspectLengthPrefixedSample(
                        sample = sample.bytes,
                        nalLengthSize = config.nalLengthSize,
                        vps = config.vps.single(),
                        sps = config.sps.single(),
                        pps = config.pps.single(),
                        context = "Feed " + feed.label + " fragment " + fragmentIndex +
                            " sample " + sampleIndex,
                        sampleOffset = sample.offset,
                        sampleLength = sample.length,
                        retainNalUnits = false
                    )
                    accessUnit.nalUnitRanges.forEach { nal -> addNal(sample.bytes, nal.offset, nal.length) }
                }
            }
            require(validatedAnnexBBytes <= annexBEquivalentBytes.toLong()) {
                "HEVC NAL payload exceeds the bounded Annex-B equivalent"
            }
            val extractedSampleCount = fragments.sumOf { it.samples.size }
            require(metadata.sumOf { it.sampleCount } == extractedSampleCount) {
                "Feed ${feed.label} CMAF fragment sample counts are inconsistent"
            }
            return ParsedFeed(track, config, rawParameterSets, fragments, metadata,
                nalUnits, firstVclType, validatedNalBytes, borrowedVclBytes, annexBEquivalentBytes,
                cmafInspectorNanos = cmafInspectorNanos.coerceAtLeast(0L),
                nalInspectorNanos = (System.nanoTime() - nalInspectStarted).coerceAtLeast(0L))
        } catch (error: Throwable) {
            nalUnits.clear()
            throw error
        }
    }

    private fun exactAnnexBSize(
        parameterSets: List<ByteArray>,
        fragments: List<SourceFragment>,
        nalLengthSize: Int
    ): Int {
        require(nalLengthSize in 1..4) { "Unsupported HEVC NAL length prefix" }
        var size = parameterSets.fold(0L) { sum, nal -> Math.addExact(sum, nal.size.toLong() + 4) }
        fragments.forEach { fragment ->
            fragment.samples.forEach { sample ->
                checkNotInterrupted()
                size = Math.addExact(size, annexBSizeForSample(
                    sample.bytes, sample.offset, sample.length, nalLengthSize).toLong())
            }
        }
        require(size <= Int.MAX_VALUE) { "HEVC Annex-B bitstream exceeds array capacity" }
        return size.toInt()
    }

    /** Exact output bytes for a bounded CMAF sample, including converted start codes. */
    internal fun annexBSizeForSample(bytes: ByteArray, offset: Int, sampleLength: Int, lengthSize: Int): Int {
        require(lengthSize in 1..4 && offset >= 0 && sampleLength >= 0 && offset <= bytes.size &&
            sampleLength <= bytes.size - offset) { "Invalid CMAF sample range or NAL length size" }
        if (lengthSize == 4) return sampleLength // The full inspector validates each NAL below.
        val end = Math.addExact(offset, sampleLength)
        var pos = offset
        var count = 0
        while (pos < end) {
            require(end - pos >= lengthSize) { "Truncated HEVC NAL length prefix" }
            var nalLength = 0L
            repeat(lengthSize) { nalLength = (nalLength shl 8) or (bytes[pos++].toLong() and 0xff) }
            require(nalLength >= 2 && nalLength <= end - pos) { "Invalid HEVC NAL length" }
            pos += nalLength.toInt()
            count++
            require(count <= 4096) { "Too many HEVC NAL units in a CMAF sample" }
        }
        return Math.addExact(sampleLength, Math.multiplyExact(count, 4 - lengthSize))
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
        require(top.filter { it.type !in setOf("styp", "sidx", "emsg", "prft", "free") }.map { it.type } == listOf("moof", "mdat")) {
            "Expected one moof followed by one mdat with optional CMAF metadata boxes"
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
        require(hVersion == 0 && (hFlags and 0x000001) == 0) {
            "tfhd must use default-base-is-moof without an explicit base_data_offset"
        }
        val trackId = readU32(h)
        require(trackId == metadata.trackId) { "tfhd track ID does not match inspected metadata" }
        if ((hFlags and 0x000002) != 0) {
            require(readU32(h) == 1L) { "Only sample description index 1 is supported" }
        }
        val defaultDuration = if ((hFlags and 0x000008) != 0) readU32(h) else null
        val defaultSize = if ((hFlags and 0x000010) != 0) readU32(h) else null
        val defaultFlags = if ((hFlags and 0x000020) != 0) readU32(h) else null
        require(!h.hasRemaining()) { "Unexpected trailing tfhd bytes" }

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
        require(version in 0..1 && (flags and 0x000001) != 0) { "trun needs a supported version and data offset" }
        require((flags and 0x000004) == 0 || (flags and 0x000400) == 0) { "trun cannot carry both first-sample and per-sample flags" }
        val sampleCount = readU32(t)
        require(sampleCount == metadata.sampleCount.toLong()) { "trun sample count differs from inspected metadata" }
        val dataOffset = t.int.toLong()
        val firstSampleFlags = if ((flags and 0x000004) != 0) readU32(t) else null
        val sampleStart = Math.addExact(moof.start.toLong(), dataOffset)
        require(sampleStart >= mdat.payloadStart.toLong() && sampleStart <= mdat.end.toLong()) {
            "trun data offset does not point into mdat"
        }
        val samples = ArrayList<SourceSample>(metadata.sampleCount)
        var cursor = sampleStart
        repeat(metadata.sampleCount) { index ->
            val timing = metadata.sampleTimings[index]
            val duration = if ((flags and 0x000100) != 0) readU32(t) else defaultDuration ?: requireNotNull(timing.durationTicks)
            val size = if ((flags and 0x000200) != 0) readU32(t) else defaultSize ?: requireNotNull(timing.sampleSizeBytes)
            val sampleFlags = if ((flags and 0x000400) != 0) readU32(t) else
                (if (index == 0) firstSampleFlags else null) ?: defaultFlags ?: requireNotNull(timing.sampleFlags)
            val compositionOffset = if ((flags and 0x000800) == 0) 0L else if (version == 0) readU32(t) else t.int.toLong()
            require(duration > 0 && size > 0 && size <= Int.MAX_VALUE) {
                "Invalid duration or size in trun sample $index"
            }
            val end = Math.addExact(cursor, size)
            require(cursor >= mdat.payloadStart && end <= mdat.end.toLong()) {
                "Sample $index extends outside mdat"
            }
            require(timing.durationTicks == duration &&
                timing.compositionOffsetTicks == compositionOffset &&
                timing.sampleFlags == sampleFlags &&
                timing.sampleSizeBytes == size) {
                "trun sample $index differs from independently inspected timing metadata"
            }
            samples += SourceSample(
                bytes, cursor.toInt(), size.toInt(),
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

    private fun appendAnnexB(out: ByteArray, offset: Int, nal: ByteArray): Int {
        val end = Math.addExact(offset, Math.addExact(nal.size, 4))
        check(end <= out.size) { "HEVC Annex-B payload exceeds its validated size" }
        out[offset] = 0; out[offset + 1] = 0; out[offset + 2] = 0; out[offset + 3] = 1
        nal.copyInto(out, offset + 4)
        return end
    }

    /** Copy the already validated NAL directly from its CMAF backing array. */
    private fun appendAnnexB(out: ByteArray, offset: Int, sample: ByteArray,
                             nalOffset: Int, nalLength: Int): Int {
        require(nalOffset >= 0 && nalLength >= 2 && nalOffset <= sample.size &&
            nalLength <= sample.size - nalOffset) { "HEVC NAL range is out of bounds" }
        val end = Math.addExact(offset, Math.addExact(nalLength, 4))
        check(offset >= 0 && end <= out.size) { "HEVC Annex-B payload exceeds its validated size" }
        out[offset] = 0; out[offset + 1] = 0; out[offset + 2] = 0; out[offset + 3] = 1
        sample.copyInto(out, offset + 4, nalOffset, nalOffset + nalLength)
        return end
    }

    private fun readU24(buffer: ByteBuffer): Int =
        ((buffer.get().toInt() and 0xff) shl 16) or
            ((buffer.get().toInt() and 0xff) shl 8) or
            (buffer.get().toInt() and 0xff)

    private fun readU32(buffer: ByteBuffer): Long =
        buffer.int.toLong() and UINT32_MAX

    private val ENCRYPTION_BOXES = setOf("senc", "saiz", "saio", "sbgp", "sgpd")
    private const val UINT32_MAX = 0xffff_ffffL

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("CMAF composition interrupted for a playback or seek request")
        }
    }
}
