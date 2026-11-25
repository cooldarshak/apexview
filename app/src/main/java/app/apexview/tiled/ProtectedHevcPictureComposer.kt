package app.apexview.tiled

import java.math.BigInteger

/**
 * Cross-feed gate and orchestrator for one protected HEVC picture.
 *
 * Inputs are individual protected CMAF samples. This validates each sample's cbcs defaults,
 * per-sample IV identity, subsample map, POC, picture identity and timestamps before rewriting
 * selected source tiles, or every tile of each complete source picture, into one sample.
 *
 * The output is sample bytes plus a CENC subsample map. It is not an encrypted CMAF fragment or
 * init segment. Raw KID/IV values are never exposed, and encrypted payload bytes are never read.
 */
internal object ProtectedHevcPictureComposer {
    data class FeedSample(
        val feedId: String,
        val sampleBytes: ByteArray,
        val track: CmafBoxInspector.TrackMetadata,
        val fragment: CmafBoxInspector.FragmentMetadata,
        val sampleIndex: Int,
        val sourceTileIndex: Int,
        val destinationAddress: Int,
        /** Per-feed tracker advanced through preceding samples in decode order. */
        val pocTracker: HevcPocTracker,
        /** Required for CRA (NAL type 21) when NoRaslOutputFlag is known by the caller. */
        val noRaslOutputFlag: Boolean? = null,
        /** Monotonic ordinal across the feed's complete CMAF sequence. Required with alignment proof. */
        val sequenceSampleOrdinal: Long? = null,
        /** tfdt of the exact source fragment; binds fragment-local sampleIndex to its container. */
        val fragmentBaseDecodeTimeTicks: Long? = null
    )

    data class Contribution(
        val feedId: String,
        val sourceTileIndex: Int,
        val destinationAddress: Int,
        val fullPictureOrderCount: Long
    )

    data class Result(
        val sampleBytes: ByteArray,
        val nalLengthSize: Int,
        val subsampleRanges: List<CmafBoxInspector.SubsampleEncryptionRange>,
        val encryptedByteCount: Long,
        val decodeTimeTicks: Long,
        val presentationTimeTicks: Long,
        val durationTicks: Long,
        val compositionOffsetTicks: Long,
        val timescale: Long,
        val sampleFlags: Long,
        val fullPictureOrderCount: Long,
        val contributions: List<Contribution>
    )

    internal data class PlannedPicture(
        val payload: ProtectedHevcSampleComposer.Plan,
        val nalLengthSize: Int,
        val subsampleRanges: List<CmafBoxInspector.SubsampleEncryptionRange>,
        val encryptedByteCount: Long,
        val decodeTimeTicks: Long,
        val presentationTimeTicks: Long,
        val durationTicks: Long,
        val compositionOffsetTicks: Long,
        val timescale: Long,
        val sampleFlags: Long,
        val fullPictureOrderCount: Long,
        val contributions: List<Contribution>
    ) : AutoCloseable {
        override fun close() = payload.close()
        fun materialize(): Result {
            val sample = payload.materialize()
            return Result(sample.sampleBytes, nalLengthSize, subsampleRanges, encryptedByteCount,
                decodeTimeTicks, presentationTimeTicks, durationTicks, compositionOffsetTicks,
                timescale, sampleFlags, fullPictureOrderCount, contributions)
        }
    }

    private data class PreparedFeed(
        val input: FeedSample,
        val sps: HevcParameterSets.SpsMetadata,
        val pps: HevcParameterSets.PpsMetadata,
        val vps: HevcParameterSets.VpsMetadata,
        val layout: ProtectedHevcSampleLayout.Report,
        val accessUnit: ProtectedHevcAccessUnitInspector.Report,
        val selectedSlice: ProtectedHevcAccessUnitInspector.SliceRegion,
        val pictureIdentity: HevcPictureIdentityCompatibility.PictureIdentity,
        val protection: CmafBoxInspector.TrackEncryptionMetadata,
        val sampleEncryption: CmafBoxInspector.SampleEncryptionMetadata,
        val timing: CmafBoxInspector.SampleTiming,
        val timescale: Long,
        val pocTrackerAfter: HevcPocTracker
    )

    fun compose(
        feeds: List<FeedSample>,
        outputSps: HevcParameterSets.SpsMetadata,
        outputPps: HevcParameterSets.PpsMetadata,
        outputNalLengthSize: Int = 4,
        context: String = "Protected HEVC picture composition",
        alignmentProof: HevcPictureSequenceAligner.Result? = null,
        /** Null retains the tile-selection POC. A value preserves every tile of each feed. */
        fullPictureColumns: Int? = null,
        /** Mixed full-frame layout verified against the same source-cell model as clear VOD. */
        mixedCells: List<HevcTizenGridComposer.CellSelection>? = null,
    ): Result {
        return plan(feeds, outputSps, outputPps, outputNalLengthSize, context,
            alignmentProof, fullPictureColumns, mixedCells).use { it.materialize() }
    }

    fun plan(
        feeds: List<FeedSample>,
        outputSps: HevcParameterSets.SpsMetadata,
        outputPps: HevcParameterSets.PpsMetadata,
        outputNalLengthSize: Int = 4,
        context: String = "Protected HEVC picture composition",
        alignmentProof: HevcPictureSequenceAligner.Result? = null,
        /** Null retains the tile-selection POC. A value preserves every tile of each feed. */
        fullPictureColumns: Int? = null,
        /** Mixed full-frame layout verified against the same source-cell model as clear VOD. */
        mixedCells: List<HevcTizenGridComposer.CellSelection>? = null,
        nativePlan: NativeProtectedPlan? = null,
    ): PlannedPicture {
        require(feeds.size in MIN_FEEDS..MAX_FEEDS) {
            "$context: between $MIN_FEEDS and $MAX_FEEDS protected feeds are supported"
        }
        require(outputNalLengthSize in 1..4) {
            "$context: output NAL length size must be between one and four bytes"
        }
        require(feeds.indices.all { first ->
            (first + 1 until feeds.size).none { second ->
                feeds[first].pocTracker === feeds[second].pocTracker
            }
        }) {
            "$context: each feed requires its own independent POC tracker"
        }
        require(feeds.all { it.feedId.isNotBlank() } &&
            feeds.map { it.feedId }.distinct().size == feeds.size) {
            "$context: feed IDs must be non-empty and unique"
        }
        val expectedTileStarts = outputTileStarts(outputSps, outputPps)
        require(expectedTileStarts != null) { "$context: output PPS tile geometry is incomplete" }
        require(mixedCells == null || fullPictureColumns == null) {
            "$context: conflicting protected tile placement plans"
        }
        if (fullPictureColumns == null && mixedCells == null) {
            require(expectedTileStarts.size == feeds.size) {
                "$context: output PPS tile count must exactly match the selected feed count"
            }
            require(feeds.map { it.destinationAddress }.distinct().size == feeds.size &&
                feeds.map { it.destinationAddress }.toSet() == expectedTileStarts) {
                "$context: each output tile must receive exactly one selected feed"
            }
        } else if (fullPictureColumns != null) require(fullPictureColumns in 1..feeds.size) {
            "$context: invalid full-picture column count"
        } else requireNotNull(mixedCells).let { cells ->
            require(cells.isNotEmpty() && cells.size == expectedTileStarts.size &&
                cells.map { it.outputColumn to it.outputRow }.distinct().size == cells.size) {
                "$context: mixed tile placements do not cover the output grid"
            }
        }

        val prepared = feeds.mapIndexed { index, input ->
            prepareFeed(input, "feed $index (" + input.feedId + ")", context)
        }
        val first = prepared.first()
        if (alignmentProof != null) {
            validateAlignmentProof(alignmentProof, prepared, context)
        }

        prepared.drop(1).forEachIndexed { index, candidate ->
            val label = "feed " + (index + 1) + " (" + candidate.input.feedId + ")"
            require(mixedCells != null ||
                candidate.input.track.width == first.input.track.width &&
                candidate.input.track.height == first.input.track.height) {
                "$context: $label source picture dimensions differ"
            }
            val parameterCompatibility = HevcParameterSetCompatibility.compare(
                first.vps, first.sps, first.pps,
                candidate.vps, candidate.sps, candidate.pps
            )
            require(parameterCompatibility.compatible) {
                "$context: $label source HEVC coding configuration is incompatible: " +
                    parameterCompatibility.reasons.joinToString("; ")
            }
            compareProtection(first, candidate, label, context)
            compareTiming(
                first, candidate, label, context,
                allowProvenTimestampOffset = alignmentProof != null
            )
            val pictureCompatibility = HevcPictureIdentityCompatibility.compare(
                listOf(first.pictureIdentity), listOf(candidate.pictureIdentity)
            )
            require(pictureCompatibility.compatible) {
                "$context: $label does not contain the same coded picture: " +
                    pictureCompatibility.reasons.joinToString("; ")
            }
        }

        data class Placement(val feed: PreparedFeed, val slice: ProtectedHevcAccessUnitInspector.SliceRegion, val address: Int)
        val placements = if (mixedCells != null) {
            val cols = requireNotNull(outputPps.tileColumnWidthsInCtbs)
            val rows = requireNotNull(outputPps.tileRowHeightsInCtbs)
            require(mixedCells.size == cols.size * rows.size) {
                "$context: mixed output tile grid is incomplete"
            }
            prepared.forEach { feed ->
                val sourceCols = requireNotNull(feed.pps.tileColumnWidthsInCtbs)
                val sourceRows = requireNotNull(feed.pps.tileRowHeightsInCtbs)
                require(feed.accessUnit.slices.size == sourceCols.size * sourceRows.size &&
                    feed.accessUnit.slices.map { it.tile.index }.toSet() ==
                        (0 until sourceCols.size * sourceRows.size).toSet() &&
                    feed.accessUnit.slices.all { it.readyForCurrentHeaderRewriter }) {
                    "$context: mixed source requires every independent clear-header tile"
                }
            }
            mixedCells.map { cell ->
                require(cell.feedIndex in prepared.indices &&
                    cell.outputColumn in cols.indices && cell.outputRow in rows.indices) {
                    "$context: mixed source/output tile index is invalid"
                }
                val feed = prepared[cell.feedIndex]
                val slice = feed.accessUnit.slices.singleOrNull { it.tile.index == cell.sourceTileIndex }
                    ?: throw IllegalArgumentException("$context: missing protected source tile")
                val address = rows.take(cell.outputRow).sum() * outputSps.picWidthInCtbs +
                    cols.take(cell.outputColumn).sum()
                require(slice.tile.widthInCtbs == cols[cell.outputColumn] &&
                    slice.tile.heightInCtbs == rows[cell.outputRow]) {
                    "$context: protected source tile geometry differs from the output PPS"
                }
                Placement(feed, slice, address)
            }.sortedBy { it.address }
        } else if (fullPictureColumns == null) prepared.map { Placement(it, it.selectedSlice, it.input.destinationAddress) }
        else {
            val columns = fullPictureColumns
            val rows = (feeds.size + columns - 1) / columns
            val sourceColumns = requireNotNull(first.pps.tileColumnWidthsInCtbs)
            val sourceRows = requireNotNull(first.pps.tileRowHeightsInCtbs)
            require(outputSps.picWidthInCtbs == columns * first.sps.picWidthInCtbs &&
                outputSps.picHeightInCtbs == rows * first.sps.picHeightInCtbs &&
                outputPps.tileColumnWidthsInCtbs == List(columns) { sourceColumns }.flatten() &&
                outputPps.tileRowHeightsInCtbs == List(rows) { sourceRows }.flatten()) {
                "$context: output PPS does not preserve complete source pictures"
            }
            buildList {
                repeat(columns * rows) { slot ->
                    // A partly filled final atlas row repeats the last source. UI only samples
                    // the original feed count. Ciphertext and crypto metadata are unchanged.
                    val feed = prepared[minOf(slot, prepared.lastIndex)]
                    require(feed.accessUnit.slices.size == sourceColumns.size * sourceRows.size &&
                        feed.accessUnit.slices.map { it.tile.index }.toSet() == (0 until sourceColumns.size * sourceRows.size).toSet() &&
                        feed.accessUnit.slices.all { it.readyForCurrentHeaderRewriter }) {
                        "$context: a complete source picture requires every independent clear-header tile"
                    }
                    feed.accessUnit.slices.forEach { slice ->
                        val x = (slot % columns) * feed.sps.picWidthInCtbs + slice.tile.leftCtb
                        val y = (slot / columns) * feed.sps.picHeightInCtbs + slice.tile.topCtb
                        add(Placement(feed, slice, y * outputSps.picWidthInCtbs + x))
                    }
                }
            }.sortedBy { it.address }
        }
        require(placements.map { it.address }.toSet() == expectedTileStarts &&
            placements.size == expectedTileStarts.size) { "$context: every output tile must be covered once" }
        val rewritten = mutableListOf<ProtectedHevcSliceHeaderRewriter.Borrowed>()
        var composed: ProtectedHevcSampleComposer.Plan? = null
        try {
        placements.forEach { (feed, slice, address) ->
            rewritten += ProtectedHevcSliceHeaderRewriter.rewriteBorrowed(
                sample = feed.input.sampleBytes,
                layout = feed.layout,
                accessUnit = feed.accessUnit,
                nalIndex = slice.nalIndex,
                sourceSps = feed.sps,
                sourcePps = feed.pps,
                outputSps = outputSps,
                outputPps = outputPps,
                destinationAddress = address,
                context = context + " (" + feed.input.feedId + ")"
            )
            if (nativePlan != null) {
                val config = requireNotNull(feed.input.track.hevcConfiguration)
                val mapped = feed.layout.nals.single { it.index == slice.nalIndex }
                nativePlan.verifySlice(config.rawVpsNals + config.rawSpsNals + config.rawPpsNals,
                    feed.input.sampleBytes, mapped, rewritten.last())
            }
        }
        val payload = ProtectedHevcSampleComposer.planBorrowed(
            slices = rewritten,
            nalLengthSize = outputNalLengthSize,
            context = context,
            nativePlan = nativePlan
        )
        composed = payload
        // Only commit POC state after every validation, rewrite and sample-map build succeeded.
        prepared.forEach { feed ->
            feed.input.pocTracker.commitFrom(feed.pocTrackerAfter)
        }
        val timing = first.timing
        return PlannedPicture(
            payload = payload,
            nalLengthSize = payload.nalLengthSize,
            subsampleRanges = payload.subsampleRanges,
            encryptedByteCount = payload.encryptedByteCount,
            decodeTimeTicks = requireNotNull(timing.decodeTimeTicks),
            presentationTimeTicks = requireNotNull(timing.presentationTimeTicks),
            durationTicks = requireNotNull(timing.durationTicks),
            compositionOffsetTicks = requireNotNull(timing.compositionOffsetTicks),
            timescale = first.timescale,
            sampleFlags = requireNotNull(timing.sampleFlags),
            fullPictureOrderCount = first.pictureIdentity.pictureOrderCount,
            contributions = placements.map { (feed, slice, address) ->
                Contribution(
                    feedId = feed.input.feedId,
                    sourceTileIndex = slice.tile.index,
                    destinationAddress = address,
                    fullPictureOrderCount = feed.pictureIdentity.pictureOrderCount
                )
            }
        )
        } catch (failure: Throwable) {
            if (composed != null) composed.close() else rewritten.forEach { it.close() }
            throw failure
        }
    }

    private fun prepareFeed(
        input: FeedSample,
        label: String,
        context: String
    ): PreparedFeed {
        val track = input.track
        val fragment = input.fragment
        require(track.handler == "vide" && track.encrypted &&
            track.sampleEntry == "encv" && track.originalFormat in setOf("hvc1", "hev1") &&
            track.protectionScheme == "cbcs") {
            "$context: $label must be a protected cbcs HEVC video track"
        }
        val protection = requireNotNull(track.trackEncryption) {
            "$context: $label track encryption defaults are missing"
        }
        require(protection.defaultIsProtected && protection.defaultKidIdentity != null) {
            "$context: $label protected default KID identity is unavailable"
        }
        require(protection.perSampleIvSize in setOf(0, 8, 16)) {
            "$context: $label track IV size is unsupported"
        }
        if (protection.perSampleIvSize == 0) {
            require(protection.constantIvSize in setOf(8, 16) &&
                protection.constantIvIdentity != null) {
                "$context: $label constant IV identity is unavailable"
            }
        }

        // 'roll' and other generic MP4 groups are not CBCS overrides.
        // Validate every active 'seig' mapping against the sole output tenc.
        ProtectedCmafSampleGroupCompatibility.requireTrackSafe(
            track, "$context: $label"
        )
        require(fragment.sampleDescriptionIndex == 1L) {
            "$context: $label effective sample-description index is missing or unsupported"
        }
        ProtectedCmafSampleGroupCompatibility.requireFragmentEquivalent(
            track, fragment, "$context: $label"
        )
        require(fragment.sencOverrideIvSize == null &&
            fragment.sencOverrideKidIdentity == null) {
            "$context: $label PIFF/senc override fields are not supported"
        }
        require(fragment.sampleEncryptionStructureComplete &&
            fragment.sampleEncryptionCount == fragment.sampleCount.toLong() &&
            fragment.sampleEncryptionMetadata.size == fragment.sampleCount) {
            "$context: $label per-sample encryption records are incomplete"
        }
        require(fragment.subsampleEncryptionSignalled == true &&
            fragment.sampleEncryptionRangesCoverSamples) {
            "$context: $label does not expose complete explicit subsample encryption maps"
        }
        require(input.sampleIndex in 0 until fragment.sampleCount &&
            input.sampleIndex < fragment.sampleTimings.size) {
            "$context: $label selected sample index is outside the fragment"
        }
        val timing = fragment.sampleTimings[input.sampleIndex]
        require(timing.sampleSizeBytes == input.sampleBytes.size.toLong()) {
            "$context: $label selected sample size does not match timing metadata"
        }
        require(timing.sampleFlags != null) {
            "$context: $label selected sample random-access/dependency flags are unavailable"
        }
        require(timing.decodeTimeTicks != null && timing.presentationTimeTicks != null &&
            timing.durationTicks != null && timing.durationTicks > 0L &&
            timing.compositionOffsetTicks != null) {
            "$context: $label selected sample timing is incomplete"
        }

        val encryption = fragment.sampleEncryptionMetadata[input.sampleIndex]
        require(encryption.ivSize == protection.perSampleIvSize) {
            "$context: $label sample IV size differs from effective track defaults"
        }
        if (encryption.ivSize > 0) {
            require(encryption.ivIdentity != null) {
                "$context: $label sample IV identity is unavailable"
            }
        } else {
            require(protection.constantIvIdentity != null) {
                "$context: $label constant IV identity is unavailable"
            }
        }
        val ranges = requireNotNull(encryption.subsampleRanges) {
            "$context: $label selected sample does not contain an explicit subsample map"
        }
        require(ranges.isNotEmpty()) {
            "$context: $label selected sample subsample map is empty"
        }
        if (fragment.sampleAuxiliarySizesPresent || fragment.sampleAuxiliaryOffsetsPresent) {
            require(fragment.sampleAuxiliaryInfoStructureComplete &&
                fragment.sampleAuxiliaryPayloadStructureComplete &&
                fragment.sampleAuxiliaryPayloadSampleCount == fragment.sampleCount) {
                "$context: $label sample auxiliary information is incomplete"
            }
            val auxiliary = fragment.sampleAuxiliaryMetadata
            require(auxiliary != null &&
                (auxiliary.auxiliaryInfoType == null || auxiliary.auxiliaryInfoType == "cbcs") &&
                (auxiliary.auxiliaryInfoParameter == null || auxiliary.auxiliaryInfoParameter == 0L)) {
                "$context: $label sample auxiliary-info type/parameter is unsupported"
            }
        }

        val configuration = requireNotNull(track.hevcConfiguration) {
            "$context: $label HEVC decoder configuration is missing"
        }
        require(configuration.parameterSetErrors.isEmpty() &&
            configuration.vps.size == 1 && configuration.sps.size == 1 &&
            configuration.pps.size == 1 && configuration.parameterSetFingerprints.isNotEmpty()) {
            "$context: $label must expose exactly one completely parsed VPS, SPS and PPS"
        }
        val vps = configuration.vps.single()
        val sps = configuration.sps.single()
        val pps = configuration.pps.single()
        require(sps.displayWidth == track.width && sps.displayHeight == track.height) {
            "$context: $label HEVC parameter-set dimensions differ from track sample entry"
        }
        require(configuration.nalLengthSize in 1..4) {
            "$context: $label HEVC NAL length size is unsupported"
        }
        val layout = ProtectedHevcSampleLayout.inspect(
            sample = input.sampleBytes,
            nalLengthSize = configuration.nalLengthSize,
            subsampleRanges = ranges,
            context = "$context $label"
        )
        val accessUnit = ProtectedHevcAccessUnitInspector.inspectLengthPrefixedSample(
            sample = input.sampleBytes,
            layout = layout,
            sps = sps,
            pps = pps,
            vps = vps,
            context = "$context $label"
        )
        val selectedSlice = accessUnit.slices.singleOrNull {
            it.tile.index == input.sourceTileIndex
        } ?: throw IllegalArgumentException(
            "$context: $label selected source tile " + input.sourceTileIndex + " is missing or ambiguous"
        )
        require(selectedSlice.readyForCurrentHeaderRewriter) {
            "$context: $label selected source tile does not expose the clear CABAC prefix required for rewriting"
        }

        val header = selectedSlice.header
        val pocLsb = header.picOrderCntLsb ?: if (header.nalUnitType in 19..20) 0 else null
        require(pocLsb != null) {
            "$context: $label selected source tile has no picture-order-count LSB"
        }
        val noRaslOutput = input.noRaslOutputFlag ?: (header.nalUnitType in 16..20)
        val pocTrackerAfter = input.pocTracker.fork()
        val fullPoc = try {
            val derived = pocTrackerAfter.derive(
                nalUnitType = header.nalUnitType,
                temporalIdPlus1 = header.temporalIdPlus1,
                pocLsb = requireNotNull(pocLsb),
                log2MaxPocLsb = sps.log2MaxPicOrderCntLsb,
                noRaslOutputFlag = noRaslOutput
            )
            require(pocTrackerAfter.hasIrapAnchor &&
                pocTrackerAfter.irapAnchorPictureOrderCount != null) {
                "A valid temporal-id-zero IRAP reference anchor is required for protected cross-feed composition"
            }
            derived
        } catch (error: Exception) {
            val detail = error.message ?: error.javaClass.simpleName
            throw IllegalArgumentException("$context: $label full POC is unavailable: " + detail)
        }
        val pictureIdentity = HevcPictureIdentityCompatibility.identify(header, fullPoc)
        val scale = requireNotNull(track.timescale)
        require(scale > 0L && (fragment.timescale == null || fragment.timescale == scale)) {
            "$context: $label track/fragment timescale is missing or inconsistent"
        }

        return PreparedFeed(
            input, sps, pps, vps, layout, accessUnit, selectedSlice,
            pictureIdentity, protection, encryption, timing, scale, pocTrackerAfter
        )
    }

    private fun validateAlignmentProof(
        proof: HevcPictureSequenceAligner.Result,
        prepared: List<PreparedFeed>,
        context: String
    ) {
        require(HevcPictureSequenceAligner.isAuthentic(proof)) {
            "$context: alignment proof was not issued by the HEVC sequence aligner"
        }
        require(proof.compatible && proof.reasons.isEmpty() && proof.matches.isNotEmpty()) {
            "$context: alignment proof is incomplete or rejected"
        }

        val feedIds = prepared.map { it.input.feedId }
        val feedIdSet = feedIds.toSet()
        require(proof.masterFeedId == feedIds.first()) {
            "$context: designated main feed differs from the alignment proof master"
        }
        require(proof.offsetsByFeed.keys == feedIdSet) {
            "$context: alignment proof does not contain exactly the selected feeds"
        }
        val anchorPoc = requireNotNull(proof.irapAnchorPictureOrderCount) {
            "$context: alignment proof has no verified IRAP epoch"
        }
        val masterOffset = proof.offsetsByFeed[feedIds.first()]
        require(masterOffset != null && masterOffset.numerator == BigInteger.ZERO &&
            masterOffset.denominator == BigInteger.ONE) {
            "$context: alignment proof main-feed offset is invalid"
        }

        val masterPrepared = prepared.first()
        val masterInput = masterPrepared.input
        val picturePoc = masterPrepared.pictureIdentity.pictureOrderCount
        // The real F1 288-sample CMAF fragments contain IDR resets and therefore
        // repeated numeric POCs. POC-only proof lookup incorrectly rejects the
        // second and later epochs even after valid sequence alignment.
        val match = HevcPictureSequenceAligner.findMasterMatch(
            proof = proof,
            feedId = masterInput.feedId,
            fragmentBaseDecodeTimeTicks = requireNotNull(masterInput.fragmentBaseDecodeTimeTicks) {
                "$context: selected main-feed CMAF tfdt evidence is missing"
            },
            sampleIndex = masterInput.sampleIndex,
            sequenceSampleOrdinal = requireNotNull(masterInput.sequenceSampleOrdinal) {
                "$context: selected main-feed global ordinal is missing"
            },
            irapEpochOrdinal = masterPrepared.pocTrackerAfter.irapEpochOrdinal,
            fullPictureOrderCount = picturePoc
        ) ?: throw IllegalArgumentException(
            "$context: no unique aligned picture matches the selected fragment, ordinal, " +
                "IRAP epoch and POC"
        )
        require(match.sampleIndicesByFeed.keys == feedIdSet &&
            match.sampleEvidenceByFeed.keys == feedIdSet) {
            "$context: alignment proof sample evidence is incomplete or contains unexpected feeds"
        }

        prepared.forEach { feed ->
            val input = feed.input
            val evidence = match.sampleEvidenceByFeed.getValue(input.feedId)
            require(input.sequenceSampleOrdinal != null &&
                input.sequenceSampleOrdinal == evidence.sequenceSampleOrdinal) {
                "$context: " + input.feedId + " global sequence sample ordinal is missing or stale"
            }
            require(input.sampleIndex == evidence.sampleIndex &&
                match.sampleIndicesByFeed[input.feedId] == evidence.sampleIndex) {
                "$context: " + input.feedId + " selected fragment sample is outside the alignment proof"
            }
            require(feed.timescale == evidence.timescale && feed.timing == evidence.timing) {
                "$context: " + input.feedId + " selected sample timing differs from the inspected alignment evidence"
            }
            require(input.fragmentBaseDecodeTimeTicks != null &&
                input.fragmentBaseDecodeTimeTicks == evidence.fragmentBaseDecodeTimeTicks &&
                input.fragment.baseDecodeTimeTicks == evidence.fragmentBaseDecodeTimeTicks) {
                "$context: " + input.feedId +
                    " selected sample belongs to a different or unverified CMAF fragment tfdt"
            }
            require(feed.pictureIdentity == evidence.picture) {
                "$context: " + input.feedId + " selected full picture identity differs from the alignment proof"
            }
            val masterEvidence = match.sampleEvidenceByFeed.getValue(feedIds.first())
            require(evidence.irapAnchorPictureOrderCount != null &&
                masterEvidence.irapAnchorPictureOrderCount != null &&
                evidence.irapAnchorPictureOrderCount ==
                    masterEvidence.irapAnchorPictureOrderCount &&
                feed.pocTrackerAfter.hasIrapAnchor &&
                feed.pocTrackerAfter.irapAnchorPictureOrderCount ==
                    evidence.irapAnchorPictureOrderCount &&
                feed.pocTrackerAfter.irapEpochOrdinal == evidence.irapEpochOrdinal) {
                "$context: " + input.feedId +
                    " selected sample does not belong to the verified aligned IRAP epoch"
            }
        }

        val master = prepared.first()
        prepared.drop(1).forEach { feed ->
            val expected = proof.offsetsByFeed.getValue(feed.input.feedId)
            val dtsOffset = exactTimestampOffset(
                requireNotNull(master.timing.decodeTimeTicks), master.timescale,
                requireNotNull(feed.timing.decodeTimeTicks), feed.timescale
            )
            val ptsOffset = exactTimestampOffset(
                requireNotNull(master.timing.presentationTimeTicks), master.timescale,
                requireNotNull(feed.timing.presentationTimeTicks), feed.timescale
            )
            require(dtsOffset.first == expected.numerator &&
                dtsOffset.second == expected.denominator) {
                "$context: " + feed.input.feedId + " actual DTS offset differs from the exact proven offset"
            }
            require(ptsOffset.first == expected.numerator &&
                ptsOffset.second == expected.denominator) {
                "$context: " + feed.input.feedId + " actual PTS offset differs from the exact proven offset"
            }
        }
    }

    private fun exactTimestampOffset(
        masterTicks: Long,
        masterTimescale: Long,
        feedTicks: Long,
        feedTimescale: Long
    ): Pair<BigInteger, BigInteger> {
        require(masterTimescale > 0L && feedTimescale > 0L) {
            "Timestamp timescales must be positive"
        }
        val numerator = BigInteger.valueOf(feedTicks)
            .multiply(BigInteger.valueOf(masterTimescale))
            .subtract(BigInteger.valueOf(masterTicks).multiply(BigInteger.valueOf(feedTimescale)))
        val denominator = BigInteger.valueOf(masterTimescale)
            .multiply(BigInteger.valueOf(feedTimescale))
        val divisor = numerator.abs().gcd(denominator)
        return numerator.divide(divisor) to denominator.divide(divisor)
    }

    private fun compareProtection(
        first: PreparedFeed,
        second: PreparedFeed,
        label: String,
        context: String
    ) {
        val a = first.protection
        val b = second.protection
        val shapeA = a.copy(defaultKidIdentity = null, constantIvIdentity = null)
        val shapeB = b.copy(defaultKidIdentity = null, constantIvIdentity = null)
        require(shapeA == shapeB) {
            "$context: $label cbcs scheme, IV size or encryption pattern differs"
        }
        require(a.defaultKidIdentity == b.defaultKidIdentity) {
            "$context: $label default KID identity differs"
        }
        if (a.perSampleIvSize == 0) {
            require(a.constantIvIdentity == b.constantIvIdentity) {
                "$context: $label constant IV identity differs"
            }
        } else {
            require(first.sampleEncryption.ivSize == second.sampleEncryption.ivSize &&
                first.sampleEncryption.ivIdentity == second.sampleEncryption.ivIdentity) {
                "$context: $label selected sample IV identity differs"
            }
        }
    }

    private fun compareTiming(
        first: PreparedFeed,
        second: PreparedFeed,
        label: String,
        context: String,
        allowProvenTimestampOffset: Boolean
    ) {
        val a = first.timing
        val b = second.timing
        if (!allowProvenTimestampOffset) {
            compareMediaTime("decode time", a.decodeTimeTicks, first.timescale,
                b.decodeTimeTicks, second.timescale, label, context)
            compareMediaTime("presentation time", a.presentationTimeTicks, first.timescale,
                b.presentationTimeTicks, second.timescale, label, context)
        }
        compareMediaTime("sample duration", a.durationTicks, first.timescale,
            b.durationTicks, second.timescale, label, context)
        compareMediaTime("composition offset", a.compositionOffsetTicks, first.timescale,
            b.compositionOffsetTicks, second.timescale, label, context)
        val flagsA = requireNotNull(a.sampleFlags)
        val flagsB = requireNotNull(b.sampleFlags)
        require((flagsA and RESERVED_SAMPLE_FLAGS_MASK) == 0L &&
            (flagsB and RESERVED_SAMPLE_FLAGS_MASK) == 0L &&
            ((flagsA xor flagsB) and RANDOM_ACCESS_DEPENDENCY_MASK) == 0L) {
            "$context: $label sample sync/random-access dependency flags differ or are invalid"
        }
    }

    private fun compareMediaTime(
        label: String,
        aTicks: Long?,
        aScale: Long,
        bTicks: Long?,
        bScale: Long,
        feedLabel: String,
        context: String
    ) {
        require(aTicks != null && bTicks != null) {
            "$context: $feedLabel $label is unavailable"
        }
        val left = BigInteger.valueOf(aTicks).multiply(BigInteger.valueOf(bScale))
        val right = BigInteger.valueOf(bTicks).multiply(BigInteger.valueOf(aScale))
        require(left == right) {
            "$context: $feedLabel $label differs; timestamp offsets are not silently rebased"
        }
    }

    private fun outputTileStarts(
        sps: HevcParameterSets.SpsMetadata,
        pps: HevcParameterSets.PpsMetadata
    ): Set<Int>? {
        val columns = pps.tileColumnWidthsInCtbs ?: return null
        val rows = pps.tileRowHeightsInCtbs ?: return null
        if (columns.isEmpty() || rows.isEmpty() ||
            columns.size != pps.numTileColumns || rows.size != pps.numTileRows ||
            columns.any { it <= 0 } || rows.any { it <= 0 }) return null
        if (columns.sumOf { it.toLong() } != sps.picWidthInCtbs.toLong() ||
            rows.sumOf { it.toLong() } != sps.picHeightInCtbs.toLong()) return null
        if (!pps.tilesEnabled && (columns.size != 1 || rows.size != 1)) return null
        val starts = linkedSetOf<Int>()
        var top = 0
        rows.forEach { rowHeight ->
            var left = 0
            columns.forEach { columnWidth ->
                starts += Math.addExact(Math.multiplyExact(top, sps.picWidthInCtbs), left)
                left = Math.addExact(left, columnWidth)
            }
            top = Math.addExact(top, rowHeight)
        }
        return starts
    }

    private const val MIN_FEEDS = 2
    private const val MAX_FEEDS = 8
    private const val RESERVED_SAMPLE_FLAGS_MASK = 0xf0000000L
    private const val RANDOM_ACCESS_DEPENDENCY_MASK = 0x0fff0000L
}
