package app.apexview.tiled

import java.math.BigInteger
import java.util.LinkedHashMap

/**
 * Runtime path from selected, authorized F1 CMAF responses to a protected composed CMAF fragment.
 *
 * It stores only the active protected init and one bounded source-fragment sample window per
 * selected feed. Sample bytes and IVs are zeroed when a window is replaced or the selection clears.
 * No media is decrypted, no DRM key is requested, and no independent-feed output is generated.
 *
 * The current runtime entry point is deliberately limited to the exact 2/3/4/6-feed layouts
 * supported by the output grid. Source tile zero is selected for each feed until the UI exposes
 * per-cell source tile selection.
 */
internal object ProtectedHevcLiveComposition {
    data class OutputSegment(
        val initializationSegment: ByteArray,
        val mediaFragment: ByteArray,
        val timescale: Long,
        val baseDecodeTimeTicks: Long,
        val sampleCount: Int,
        val width: Int,
        val height: Int,
        val alignmentOffsetsMillis: Map<String, Long?>
    ) {
        fun zero() {
            initializationSegment.fill(0)
            mediaFragment.fill(0)
        }
    }

    /** One live output fragment. The init is shared by the bounded window, not copied per segment. */
    data class OutputFragment(
        val sequenceNumber: Long,
        val mediaFragment: ByteArray,
        val timescale: Long,
        val baseDecodeTimeTicks: Long,
        val durationTicks: Long,
        val sampleCount: Int,
        val width: Int,
        val height: Int,
        val alignmentOffsetsMillis: Map<String, Long?>
    ) {
        fun zero() = mediaFragment.fill(0)

        fun detached() = copy(
            mediaFragment = mediaFragment.copyOf(),
            alignmentOffsetsMillis = alignmentOffsetsMillis.toMap()
        )
    }

    data class OutputWindow(
        val initializationSegment: ByteArray,
        val fragments: List<OutputFragment>
    ) {
        fun zero() {
            initializationSegment.fill(0)
            fragments.forEach(OutputFragment::zero)
        }
    }

    private data class BufferedPicture(
        val picture: HevcPictureSequenceAligner.PictureSample,
        val sampleBytes: ByteArray,
        val perSampleIvBytes: ByteArray?,
        val perSampleIvIdentity: ProtectedValueFingerprint?,
        val track: CmafBoxInspector.TrackMetadata,
        val fragment: CmafBoxInspector.FragmentMetadata,
        val noRaslOutputFlag: Boolean,
        val pocTrackerBefore: HevcPocTracker
    ) {
        fun zero() {
            sampleBytes.fill(0)
            perSampleIvBytes?.fill(0)
        }
    }

    private class FeedState(val label: String) {
        var initializationSegment: ByteArray? = null
        var initializationReport: CmafBoxInspector.Report? = null
        var track: CmafBoxInspector.TrackMetadata? = null
        var representationSignature: String? = null
        val pocTracker = HevcPocTracker()
        var nextSequenceOrdinal: Long = 0L
        var sawCodedPicture: Boolean = false
        var afterEndOfSequence: Boolean = false
        var window: List<HevcPictureSequenceAligner.PictureSample> = emptyList()
        val picturesByOrdinal = linkedMapOf<Long, BufferedPicture>()

        fun clearWindow() {
            picturesByOrdinal.values.forEach(BufferedPicture::zero)
            picturesByOrdinal.clear()
            window = emptyList()
        }

        fun clearAll() {
            clearWindow()
            initializationSegment?.fill(0)
            initializationSegment = null
            initializationReport = null
            track = null
            representationSignature = null
            pocTracker.reset()
            nextSequenceOrdinal = 0L
            sawCodedPicture = false
            afterEndOfSequence = false
        }
    }

    private class Session(
        val group: String,
        val labels: List<String>,
        val sourceTileIndices: List<Int>
    ) {
        val feeds = labels.associateWith(::FeedState)
        var status: String = "WAITING_FOR_ACTIVE_PROTECTED_INIT_SEGMENTS"
        var outputInitializationSegment: ByteArray? = null
        var outputRepresentationSignature: String? = null
        var outputSequenceNumber: Long = 1L
        var lastComposedMasterSequenceOrdinal: Long = -1L
        val outputHistory = mutableListOf<OutputFragment>()
        var composedFragments: Long = 0L
        var lastFailure: String? = null

        fun clear() {
            feeds.values.forEach(FeedState::clearAll)
            outputInitializationSegment?.fill(0)
            outputInitializationSegment = null
            outputRepresentationSignature = null
            outputHistory.forEach(OutputFragment::zero)
            outputHistory.clear()
            status = "CLEARED"
        }
    }

    private data class PendingInitialization(
        val bytes: ByteArray,
        val report: CmafBoxInspector.Report
    ) {
        fun zero() = bytes.fill(0)
    }

    private val pendingInitializations =
        linkedMapOf<String, LinkedHashMap<String, PendingInitialization>>()
    private val sessions = linkedMapOf<String, Session>()

    @Synchronized
    fun selectFeeds(
        group: String,
        labels: List<String>,
        sourceTileIndices: List<Int> = List(labels.size) { 0 }
    ) {
        require(group.isNotBlank()) { "Protected composition group is required" }
        val selected = labels.distinct()
        require(selected.size in setOf(2, 3, 4, 6) && selected.all(String::isNotBlank)) {
            "Protected composition supports exactly 2, 3, 4 or 6 distinct feeds"
        }
        require(sourceTileIndices.size == selected.size &&
            sourceTileIndices.all { it >= 0 }) {
            "One non-negative source tile index is required per selected feed"
        }
        sessions.remove(group)?.clear()
        val session = Session(group, selected, sourceTileIndices)
        sessions[group] = session
        // Media3 may already have fetched the active init before the user selects feeds.
        // Reuse only the bounded, paired init snapshot; media fragments are captured after selection.
        pendingInitializations[group]?.let { pending ->
            selected.forEach { label ->
                val cached = pending[label] ?: return@forEach
                runCatching {
                    captureInitialization(session, session.feeds.getValue(label), cached.bytes, cached.report)
                }.onFailure { error ->
                    stateFailure(session, label + ": cached init rejected: " +
                        (error.message ?: error.javaClass.simpleName).take(160))
                }
            }
        }
        while (sessions.size > MAX_SESSIONS) {
            val oldest = sessions.keys.first()
            sessions.remove(oldest)?.clear()
        }
    }

    @Synchronized
    fun clearSelection(group: String) {
        sessions.remove(group)?.clear()
    }

    @Synchronized
    fun clearAll() {
        sessions.values.forEach(Session::clear)
        sessions.clear()
        pendingInitializations.values.flatMap { it.values }.forEach(PendingInitialization::zero)
        pendingInitializations.clear()
    }

    /**
     * Called from the existing CMAF response observer while the bytes are still available.
     * Initialization and media are always paired again by the strict protected extractor.
     */
    @Synchronized
    fun recordCmafResponse(
        group: String,
        label: String,
        bytes: ByteArray,
        report: CmafBoxInspector.Report
    ) {
        val session = sessions[group]
        try {
            if (report.initializationSegmentPresent) {
                // DASH audio and video commonly use separate init segments. Only retain the
                // protected HEVC video init; audio init responses are not composition inputs.
                if (report.tracks.none { it.handler == "vide" }) return
                if (report.errors.isNotEmpty()) {
                    if (session != null) stateFailure(
                        session, "$label: active video CMAF initialization did not parse cleanly"
                    )
                    return
                }
                cacheInitialization(group, label, bytes, report)
                val state = session?.feeds?.get(label)
                if (session != null && state != null) {
                    captureInitialization(session, state, bytes, report)
                }
            } else if (session != null) {
                val state = session.feeds[label] ?: return
                val activeVideoTrackId = state.track?.trackId ?: return
                // Ignore audio-only fragments. A video response with multiple trafs remains
                // unsupported until the extractor can validate and select one traf unambiguously.
                if (report.fragments.none { it.trackId == activeVideoTrackId }) return
                if (report.errors.isNotEmpty()) {
                    stateFailure(session, "$label: active protected video fragment did not parse cleanly")
                    return
                }
                captureMediaFragment(session, state, bytes, report)
                tryCompose(session)
            }
        } catch (error: Exception) {
            if (session != null) {
                stateFailure(
                    session,
                    label + ": " + (error.message ?: error.javaClass.simpleName)
                        .filter { it.isLetterOrDigit() || it in " .,:;_-/()" }
                        .take(220)
                )
            }
        }
    }

    @Synchronized
    fun status(group: String): String = sessions[group]?.let { session ->
        buildString {
            append(session.status)
            append("; feeds=")
            append(session.labels.joinToString(","))
            append("; composedFragments=")
            append(session.composedFragments)
            session.lastFailure?.let {
                append("; lastReject=")
                append(it)
            }
        }
    } ?: "PROTECTED_COMPOSITION_NOT_SELECTED"

    /** Returns the latest output for diagnostics and one-fragment playback. */
    @Synchronized
    fun latestOutput(group: String): OutputSegment? {
        val session = sessions[group] ?: return null
        val init = session.outputInitializationSegment ?: return null
        val output = session.outputHistory.lastOrNull() ?: return null
        return OutputSegment(
            initializationSegment = init.copyOf(),
            mediaFragment = output.mediaFragment.copyOf(),
            timescale = output.timescale,
            baseDecodeTimeTicks = output.baseDecodeTimeTicks,
            sampleCount = output.sampleCount,
            width = output.width,
            height = output.height,
            alignmentOffsetsMillis = output.alignmentOffsetsMillis.toMap()
        )
    }

    /** A bounded rolling output window for the live DASH representation. */
    @Synchronized
    fun outputWindow(group: String): OutputWindow? {
        val session = sessions[group] ?: return null
        val init = session.outputInitializationSegment ?: return null
        if (session.outputHistory.isEmpty()) return null
        return OutputWindow(
            initializationSegment = init.copyOf(),
            fragments = session.outputHistory.map(OutputFragment::detached)
        )
    }

    @Synchronized
    fun hasOutput(group: String): Boolean =
        sessions[group]?.let {
            it.outputInitializationSegment != null && it.outputHistory.isNotEmpty()
        } == true

    private fun cacheInitialization(
        group: String,
        label: String,
        bytes: ByteArray,
        report: CmafBoxInspector.Report
    ) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_INIT_BYTES) {
            "active CMAF initialization segment exceeds its memory bound"
        }
        require(report.initializationSegmentPresent) {
            "only complete initialization responses can be cached"
        }
        val groupCache = pendingInitializations.getOrPut(group) { linkedMapOf() }
        groupCache.remove(label)?.zero()
        groupCache[label] = PendingInitialization(bytes.copyOf(), report)
        while (groupCache.size > MAX_PENDING_FEEDS_PER_GROUP) {
            val oldest = groupCache.keys.first()
            groupCache.remove(oldest)?.zero()
        }
        while (pendingInitializations.size > MAX_PENDING_GROUPS) {
            val oldestGroup = pendingInitializations.keys.first()
            pendingInitializations.remove(oldestGroup)?.values?.forEach(PendingInitialization::zero)
        }
    }

    private fun captureInitialization(
        session: Session,
        state: FeedState,
        bytes: ByteArray,
        report: CmafBoxInspector.Report
    ) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_INIT_BYTES) {
            "initialization segment is empty or exceeds its memory bound"
        }
        val videoTracks = report.tracks.filter { it.handler == "vide" }
        require(videoTracks.size == 1) {
            "active initialization must contain exactly one video track"
        }
        val track = videoTracks.single()
        require(track.encrypted && track.sampleEntry == "encv" &&
            track.originalFormat in setOf("hvc1", "hev1") &&
            track.protectionScheme == "cbcs" &&
            track.trackEncryption?.defaultIsProtected == true) {
            "active representation is not protected CBCS HEVC"
        }
        val configuration = requireNotNull(track.hevcConfiguration) {
            "active protected HEVC hvcC is missing"
        }
        require(configuration.parameterSetErrors.isEmpty() &&
            configuration.rawVpsNals.size == 1 &&
            configuration.rawSpsNals.size == 1 &&
            configuration.rawPpsNals.size == 1 &&
            configuration.vps.size == 1 &&
            configuration.sps.size == 1 &&
            configuration.pps.size == 1) {
            "active hvcC does not contain one parsed VPS/SPS/PPS triplet"
        }
        val encryption = requireNotNull(track.trackEncryption)
        require(encryption.defaultKidIdentity != null &&
            encryption.perSampleIvSize in setOf(0, 8, 16)) {
            "active tenc protection defaults are incomplete"
        }
        if (encryption.perSampleIvSize == 0) {
            require(encryption.constantIvSize in setOf(8, 16) &&
                encryption.constantIvIdentity != null) {
                "active tenc constant-IV configuration is incomplete"
            }
        }
        require(!track.sampleGroupDescriptionPresent && !track.sampleToGroupPresent &&
            track.seigSampleGroupDescription == null) {
            "track-level seig encryption overrides are unsupported"
        }

        val signature = listOf(
            track.trackId, track.width, track.height, track.timescale,
            track.originalFormat, configuration.parameterSetFingerprints,
            encryption.defaultKidIdentity, encryption.perSampleIvSize,
            encryption.cryptByteBlock, encryption.skipByteBlock,
            encryption.constantIvSize, encryption.constantIvIdentity
        ).joinToString("|")
        if (state.representationSignature != signature) {
            state.clearWindow()
            state.pocTracker.reset()
            state.nextSequenceOrdinal = 0L
            state.sawCodedPicture = false
            state.afterEndOfSequence = false
            session.outputInitializationSegment?.fill(0)
            session.outputInitializationSegment = null
            session.outputRepresentationSignature = null
            session.lastComposedMasterSequenceOrdinal = -1L
            session.outputHistory.forEach(OutputFragment::zero)
            session.outputHistory.clear()
        }
        state.initializationSegment?.fill(0)
        state.initializationSegment = bytes.copyOf()
        state.initializationReport = report
        state.track = track
        state.representationSignature = signature
        session.status = "ACTIVE_PROTECTED_CBCS_HEVC_INIT_CAPTURED"
        session.lastFailure = null
    }

    private fun captureMediaFragment(
        session: Session,
        state: FeedState,
        bytes: ByteArray,
        report: CmafBoxInspector.Report
    ) {
        val initBytes = requireNotNull(state.initializationSegment) {
            "media fragment arrived before its active initialization segment"
        }
        val activeReport = requireNotNull(state.initializationReport) {
            "active initialization metadata is not available"
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_FRAGMENT_BYTES) {
            "media fragment is empty or exceeds its capture bound"
        }
        val activeTrack = requireNotNull(state.track)
        val hevc = requireNotNull(activeTrack.hevcConfiguration)
        val sps = hevc.sps.single()
        val fragmentMetadata = report.fragments.singleOrNull()
            ?: throw IllegalArgumentException("active media response must contain exactly one CMAF track fragment")
        require(fragmentMetadata.sampleCount in 1..MAX_FRAGMENT_SAMPLE_COUNT) {
            "active media fragment sample count is outside the bounded HEVC inspection window"
        }
        require(fragmentMetadata.trackId == activeTrack.trackId) {
            "active media fragment track ID differs from the selected protected video track"
        }
        val selectedIndices = (0 until fragmentMetadata.sampleCount).toSet()
        val workingTracker = state.pocTracker.fork()
        var nextOrdinal = state.nextSequenceOrdinal
        var sawCodedPicture = state.sawCodedPicture
        var afterEndOfSequence = state.afterEndOfSequence
        val currentPictures = mutableListOf<HevcPictureSequenceAligner.PictureSample>()
        val currentBuffers = linkedMapOf<Long, BufferedPicture>()
        var visited = 0

        var windowCommitted = false
        try {
            val info = ProtectedHevcCmafSampleExtractor.forEachSample(
                initializationSegment = initBytes,
                mediaFragment = bytes,
                sampleIndices = selectedIndices,
                context = "F1 protected feed " + state.label
            ) { fragmentInfo, sample ->
                if (visited == 0) {
                    require(fragmentInfo.track.trackId == activeTrack.trackId &&
                        fragmentInfo.track.hevcConfiguration?.parameterSetFingerprints ==
                            hevc.parameterSetFingerprints) {
                        "media fragment does not match the captured active HEVC representation"
                    }
                }
                visited++
                val index = sample.sampleIndex
                val timing = fragmentInfo.fragment.sampleTimings.getOrNull(index)
                    ?: throw IllegalArgumentException("sample timing row is missing")
                val encryption = fragmentInfo.fragment.sampleEncryptionMetadata.getOrNull(index)
                    ?: throw IllegalArgumentException("sample encryption row is missing")
                val ranges = requireNotNull(encryption.subsampleRanges) {
                    "sample has no explicit CBCS subsample map"
                }
                val sampleLayout = ProtectedHevcSampleLayout.inspect(
                    sample = sample.sampleBytes,
                    nalLengthSize = hevc.nalLengthSize,
                    subsampleRanges = ranges,
                    context = "F1 protected feed " + state.label + " sample " + index
                )
                val accessUnit = ProtectedHevcAccessUnitInspector.inspectLengthPrefixedSample(
                    sample = sample.sampleBytes,
                    layout = sampleLayout,
                    sps = sps,
                    pps = hevc.pps.single(),
                    vps = hevc.vps.single(),
                    context = "F1 protected feed " + state.label + " sample " + index
                )
                require(accessUnit.slices.isNotEmpty()) {
                    "sample contains no parsed independent HEVC slice"
                }
                val header = accessUnit.slices.first().header
                val pocLsb = header.picOrderCntLsb
                    ?: if (header.nalUnitType in 19..20) 0 else null
                require(pocLsb != null) {
                    "sample has no usable POC LSB in its clear slice header"
                }
                val noRaslOutputFlag = when {
                    header.nalUnitType in 16..20 -> true
                    header.nalUnitType in 21..23 ->
                        !sawCodedPicture || afterEndOfSequence
                    else -> false
                }
                val trackerBefore = workingTracker.fork()
                val fullPoc = workingTracker.derive(
                    nalUnitType = header.nalUnitType,
                    temporalIdPlus1 = header.temporalIdPlus1,
                    pocLsb = pocLsb,
                    log2MaxPocLsb = sps.log2MaxPicOrderCntLsb,
                    noRaslOutputFlag = noRaslOutputFlag
                )
                val hasVerifiedAnchor = workingTracker.hasIrapAnchor &&
                    workingTracker.irapAnchorPictureOrderCount != null
                val anchorPoc = workingTracker.irapAnchorPictureOrderCount
                val pictureIdentity = HevcPictureIdentityCompatibility.identify(header, fullPoc)
                val currentOrdinal = nextOrdinal
                nextOrdinal = Math.addExact(nextOrdinal, 1L)
    
                val firstVclNalIndex = accessUnit.slices.minOf { it.nalIndex }
                val endOfSequenceAfterPicture = accessUnit.nals.any {
                    it.index > firstVclNalIndex && it.nalType in 36..37
                }
                afterEndOfSequence = endOfSequenceAfterPicture
                sawCodedPicture = true
    
                if (hasVerifiedAnchor && anchorPoc != null) {
                    val picture = HevcPictureSequenceAligner.PictureSample(
                        sampleIndex = index,
                        timing = timing.copy(),
                        picture = pictureIdentity,
                        irapAnchorPictureOrderCount = anchorPoc,
                        sequenceSampleOrdinal = currentOrdinal,
                        fragmentBaseDecodeTimeTicks =
                            requireNotNull(fragmentInfo.fragment.baseDecodeTimeTicks)
                    )
                    val buffered = BufferedPicture(
                        picture = picture,
                        sampleBytes = sample.sampleBytes.copyOf(),
                        perSampleIvBytes = sample.perSampleIvBytes?.copyOf(),
                        perSampleIvIdentity = encryption.ivIdentity,
                        track = fragmentInfo.track,
                        fragment = fragmentInfo.fragment,
                        noRaslOutputFlag = noRaslOutputFlag,
                        pocTrackerBefore = trackerBefore
                    )
                    currentPictures += picture
                    currentBuffers[currentOrdinal] = buffered
                }
            }
    
            require(visited == info.fragment.sampleCount && visited > 0) {
                "not every sample in the active CMAF fragment was inspected"
            }
            require(currentPictures.size <= MAX_FRAGMENT_SAMPLE_COUNT) {
                "protected picture window exceeds the supported sample count"
            }
            val decodeTimeBase = requireNotNull(info.baseDecodeTimeTicks)
            require(info.fragment.baseDecodeTimeTicks == decodeTimeBase &&
                info.timescale == activeTrack.timescale) {
                "active media fragment timescale/tfdt does not match its initialization segment"
            }
    
    
            state.clearWindow()
            state.picturesByOrdinal.putAll(currentBuffers)
            state.window = currentPictures.toList()
            state.pocTracker.commitFrom(workingTracker)
            state.nextSequenceOrdinal = nextOrdinal
            state.sawCodedPicture = sawCodedPicture
            state.afterEndOfSequence = afterEndOfSequence
            session.status = "PROTECTED_F1_SAMPLE_WINDOW_CAPTURED"
            session.lastFailure = null
            windowCommitted = true
        } catch (error: Throwable) {
            if (!windowCommitted) currentBuffers.values.forEach(BufferedPicture::zero)
            throw error
        }
    }

    private fun tryCompose(session: Session) {
        if (session.feeds.values.any { it.window.size < MIN_ALIGN_PICTURES }) {
            session.status = "WAITING_FOR_FIVE_ALIGNED_PROTECTED_F1_PICTURES"
            return
        }
        val windows = session.labels.map { label ->
            val feed = session.feeds.getValue(label)
            val timescale = requireNotNull(feed.track?.timescale)
            HevcPictureSequenceAligner.FeedWindow(
                feedId = label,
                timescale = timescale,
                samples = feed.window
            )
        }
        val proof = HevcPictureSequenceAligner.align(
            feeds = windows,
            minimumConsecutivePictures = MIN_ALIGN_PICTURES,
            context = "Live protected F1 feed alignment"
        )
        if (!proof.compatible || !HevcPictureSequenceAligner.isAuthentic(proof)) {
            session.status = "LIVE_PROTECTED_F1_ALIGNMENT_REJECTED"
            session.lastFailure = proof.reasons.joinToString("; ").take(220)
            return
        }

        val masterFeedId = session.labels.first()
        val newMatches = proof.matches.filter { match ->
            val ordinal = match.sampleEvidenceByFeed[masterFeedId]?.sequenceSampleOrdinal
            ordinal != null && ordinal > session.lastComposedMasterSequenceOrdinal
        }
        if (newMatches.isEmpty()) {
            session.status = "LIVE_PROTECTED_F1_ALIGNMENT_STABLE_NO_NEW_PICTURES"
            session.lastFailure = null
            return
        }

        val firstState = session.feeds.getValue(masterFeedId)
        val firstTrack = requireNotNull(firstState.track)
        val firstHevc = requireNotNull(firstTrack.hevcConfiguration)
        val sourceVps = firstHevc.rawVpsNals.single()
        val sourceSps = firstHevc.rawSpsNals.single()
        val sourcePps = firstHevc.rawPpsNals.single()
        val spsMetadata = firstHevc.sps.single()
        val ppsMetadata = firstHevc.pps.single()
        require(ppsMetadata.tilesEnabled &&
            ppsMetadata.tileColumnWidthsInCtbs != null &&
            ppsMetadata.tileRowHeightsInCtbs != null) {
            "active F1 source PPS does not expose an explicit tile grid"
        }
        val tileWidthInCtbs = ppsMetadata.tileColumnWidthsInCtbs.first()
        val tileHeightInCtbs = ppsMetadata.tileRowHeightsInCtbs.first()
        val ctbSize = spsMetadata.ctbSize
        require(tileWidthInCtbs > 0 && tileHeightInCtbs > 0 && ctbSize > 0) {
            "active F1 source tile geometry is invalid"
        }

        val gridColumns = when (session.labels.size) {
            2 -> 2
            3 -> 3
            4 -> 2
            6 -> 3
            else -> throw IllegalArgumentException("unsupported live composition feed count")
        }
        val gridRows = session.labels.size / gridColumns
        val outputColumns = List(gridColumns) { tileWidthInCtbs }
        val outputRows = List(gridRows) { tileHeightInCtbs }
        val outputWidth = Math.multiplyExact(
            Math.multiplyExact(gridColumns, tileWidthInCtbs), ctbSize
        )
        val outputHeight = Math.multiplyExact(
            Math.multiplyExact(gridRows, tileHeightInCtbs), ctbSize
        )
        val frameRate = deriveFrameRate(firstHevc, spsMetadata)
        val outputParameters = HevcOutputParameterSetWriter.generate(
            sourceVpsNal = sourceVps,
            sourceSpsNal = sourceSps,
            sourcePpsNal = sourcePps,
            request = HevcOutputParameterSetWriter.Request(
                vpsId = firstHevc.vps.single().vpsId,
                spsId = spsMetadata.spsId,
                ppsId = ppsMetadata.ppsId,
                codedWidth = outputWidth,
                codedHeight = outputHeight,
                tileColumnWidthsInCtbs = outputColumns,
                tileRowHeightsInCtbs = outputRows,
                loopFilterAcrossTilesEnabled =
                    requireNotNull(ppsMetadata.loopFilterAcrossTiles),
                frameRateNumerator = frameRate.first,
                frameRateDenominator = frameRate.second
            )
        )
        val outputSps = HevcParameterSets.parseSps(outputParameters.sps)
        val outputPps = HevcParameterSets.parsePps(outputParameters.pps, outputSps)
        val outputSignature = listOf(
            firstState.representationSignature,
            session.labels.size,
            session.sourceTileIndices,
            outputWidth,
            outputHeight,
            outputParameters.vps.contentHashCode(),
            outputParameters.sps.contentHashCode(),
            outputParameters.pps.contentHashCode()
        ).joinToString("|")
        if (session.outputRepresentationSignature != outputSignature ||
            session.outputInitializationSegment == null) {
            session.outputInitializationSegment?.fill(0)
            session.outputInitializationSegment =
                ProtectedHevcCmafInitSegmentWriter.rewrite(
                    source = requireNotNull(firstState.initializationSegment),
                    output = outputParameters,
                    context = "Live protected F1 composed initialization"
                )
            session.outputRepresentationSignature = outputSignature
            session.lastComposedMasterSequenceOrdinal = -1L
        }

        val outputTileStarts = buildList {
            repeat(gridRows) { row ->
                repeat(gridColumns) { column ->
                    add(row * outputSps.picWidthInCtbs + column * tileWidthInCtbs)
                }
            }
        }
        require(outputTileStarts.size == session.labels.size) {
            "output grid does not contain one tile per selected F1 feed"
        }

        val composedSamples = mutableListOf<ProtectedHevcCmafFragmentWriter.Sample>()
        val outputBytesToClear = mutableListOf<ByteArray>()
        try {
            var previousDecodeTime: Long? = null
            var previousDuration: Long? = null
            newMatches.forEach { match ->
                val feedSamples = session.labels.mapIndexed { feedIndex, label ->
                    val evidence = match.sampleEvidenceByFeed[label]
                        ?: throw IllegalArgumentException("alignment proof omitted feed " + label)
                    val buffered = session.feeds.getValue(label).picturesByOrdinal[
                        evidence.sequenceSampleOrdinal
                    ] ?: throw IllegalArgumentException(
                        "aligned protected sample bytes are no longer retained for feed " + label
                    )
                    require(buffered.picture.sampleIndex == evidence.sampleIndex &&
                        buffered.picture.fragmentBaseDecodeTimeTicks ==
                            evidence.fragmentBaseDecodeTimeTicks) {
                        "retained sample does not match the exact aligner fragment evidence"
                    }
                    ProtectedHevcPictureComposer.FeedSample(
                        feedId = label,
                        sampleBytes = buffered.sampleBytes,
                        track = buffered.track,
                        fragment = buffered.fragment,
                        sampleIndex = buffered.picture.sampleIndex,
                        sourceTileIndex = session.sourceTileIndices[feedIndex],
                        destinationAddress = outputTileStarts[feedIndex],
                        pocTracker = buffered.pocTrackerBefore.fork(),
                        noRaslOutputFlag = buffered.noRaslOutputFlag,
                        sequenceSampleOrdinal = buffered.picture.sequenceSampleOrdinal,
                        fragmentBaseDecodeTimeTicks =
                            buffered.picture.fragmentBaseDecodeTimeTicks
                    )
                }
                val composed = ProtectedHevcPictureComposer.compose(
                    feeds = feedSamples,
                    outputSps = outputSps,
                    outputPps = outputPps,
                    outputNalLengthSize = 4,
                    context = "Live protected F1 picture POC " + match.fullPictureOrderCount,
                    alignmentProof = proof
                )
                outputBytesToClear += composed.sampleBytes
                val masterBuffered = session.feeds.getValue(masterFeedId).picturesByOrdinal[
                    match.sampleEvidenceByFeed.getValue(masterFeedId).sequenceSampleOrdinal
                ] ?: throw IllegalArgumentException("main-feed protected sample bytes are unavailable")
                val timing = masterBuffered.picture.timing
                val currentDts = requireNotNull(timing.decodeTimeTicks)
                if (previousDecodeTime != null && previousDuration != null) {
                    require(currentDts == Math.addExact(previousDecodeTime, previousDuration)) {
                        "aligned output pictures are not contiguous on the main-feed decode timeline"
                    }
                }
                previousDecodeTime = currentDts
                previousDuration = composed.durationTicks
                val ivBytes = masterBuffered.perSampleIvBytes?.copyOf()
                ivBytes?.let(outputBytesToClear::add)
                composedSamples += ProtectedHevcCmafFragmentWriter.Sample(
                    sampleBytes = composed.sampleBytes,
                    subsampleRanges = composed.subsampleRanges,
                    perSampleIvBytes = ivBytes,
                    perSampleIvIdentity = masterBuffered.perSampleIvIdentity,
                    durationTicks = composed.durationTicks,
                    sampleFlags = composed.sampleFlags,
                    compositionOffsetTicks = composed.compositionOffsetTicks
                )
            }

            require(composedSamples.size >= MIN_ALIGN_PICTURES) {
                "fewer than five contiguous pictures survived protected slice composition"
            }
            val firstPicture = newMatches.first()
            val firstMasterEvidence = firstPicture.sampleEvidenceByFeed.getValue(masterFeedId)
            val firstMasterBuffer = firstState.picturesByOrdinal[
                firstMasterEvidence.sequenceSampleOrdinal
            ] ?: throw IllegalArgumentException("first main-feed sample is not retained")
            val sourceEncryption = requireNotNull(firstTrack.trackEncryption)
            val outputInit = requireNotNull(session.outputInitializationSegment)
            val firstComposedDts = requireNotNull(composedSamples.firstOrNull()).let {
                requireNotNull(firstMasterBuffer.picture.timing.decodeTimeTicks)
            }
            val mediaFragment = ProtectedHevcCmafFragmentWriter.write(
                outputInitializationSegment = outputInit,
                request = ProtectedHevcCmafFragmentWriter.Request(
                    trackId = requireNotNull(firstTrack.trackId),
                    baseDecodeTimeTicks = firstComposedDts,
                    sequenceNumber = session.outputSequenceNumber,
                    perSampleIvSize = sourceEncryption.perSampleIvSize,
                    samples = composedSamples
                ),
                context = "Live protected F1 composed fragment"
            )
            val durationTicks = composedSamples.fold(0L) { total, sample ->
                Math.addExact(total, sample.durationTicks)
            }
            val newOutput = OutputFragment(
                sequenceNumber = session.outputSequenceNumber,
                mediaFragment = mediaFragment,
                timescale = requireNotNull(firstTrack.timescale),
                baseDecodeTimeTicks = firstComposedDts,
                durationTicks = durationTicks,
                sampleCount = composedSamples.size,
                width = outputSps.displayWidth,
                height = outputSps.displayHeight,
                alignmentOffsetsMillis = proof.offsetsByFeed.mapValues { it.value.offsetMillis }
            )
            session.outputHistory += newOutput
            while (session.outputHistory.size > MAX_OUTPUT_HISTORY) {
                session.outputHistory.removeAt(0).zero()
            }
            session.outputSequenceNumber = Math.addExact(session.outputSequenceNumber, 1L)
            session.lastComposedMasterSequenceOrdinal =
                newMatches.last().sampleEvidenceByFeed.getValue(masterFeedId).sequenceSampleOrdinal
            session.composedFragments = Math.addExact(session.composedFragments, 1L)
            session.status = "REAL_PROTECTED_F1_CMAF_COMPOSED_NOT_YET_PLAYED"
            session.lastFailure = null
        } finally {
            outputBytesToClear.forEach { it.fill(0) }
        }
    }

    private fun deriveFrameRate(
        configuration: CmafBoxInspector.HevcConfiguration,
        sps: HevcParameterSets.SpsMetadata
    ): Pair<Int, Int> {
        val vps = configuration.vps.single()
        val vui = sps.vuiMetadata
        val numerator = vps.timeScale?.takeIf { it > 0L }
            ?: vui?.timeScale?.takeIf { it > 0L }
            ?: configuration.averageFrameRate.takeIf { it > 0 }?.toLong()
            ?: throw IllegalArgumentException("active HEVC configuration has no usable frame-rate timing")
        val denominator = vps.numUnitsInTick?.takeIf { it > 0L }
            ?: vui?.numUnitsInTick?.takeIf { it > 0L }
            ?: configuration.averageFrameRate.takeIf { it > 0 }?.let { 256L }
            ?: throw IllegalArgumentException("active HEVC configuration has no usable frame-rate denominator")
        val a = BigInteger.valueOf(numerator)
        val b = BigInteger.valueOf(denominator)
        val divisor = a.gcd(b)
        val reducedNumerator = a.divide(divisor).intValueExact()
        val reducedDenominator = b.divide(divisor).intValueExact()
        require(reducedNumerator in 1..240 && reducedDenominator in 1..1000) {
            "derived HEVC frame rate is outside supported bounds"
        }
        return reducedNumerator to reducedDenominator
    }

    private fun stateFailure(session: Session, reason: String) {
        session.status = "LIVE_PROTECTED_F1_COMPOSITION_REJECTED"
        session.lastFailure = reason
    }

    private const val MIN_ALIGN_PICTURES = 5
    private const val MAX_FRAGMENT_SAMPLE_COUNT = 4096
    private const val MAX_PENDING_GROUPS = 2
    private const val MAX_PENDING_FEEDS_PER_GROUP = 4
    private const val MAX_INIT_BYTES = 2 * 1024 * 1024
    private const val MAX_FRAGMENT_BYTES = 8 * 1024 * 1024
    private const val MAX_SESSIONS = 2
    private const val MAX_OUTPUT_HISTORY = 3
}
