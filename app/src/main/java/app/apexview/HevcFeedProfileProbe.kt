package app.apexview

import androidx.media3.common.Format
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import androidx.media3.common.MimeTypes
import app.apexview.tiled.HevcBitstream
import app.apexview.tiled.HevcParameterSets
import app.apexview.tiled.HevcParameterSetCompatibility
import app.apexview.tiled.CmafBoxInspector
import app.apexview.tiled.HevcFeedCompatibility
import app.apexview.tiled.HevcFragmentCompatibility

/**
 * Sanitised read-only summary of the codec configuration Media3 already parsed for a video track.
 *
 * Only VPS/SPS/PPS metadata is returned. Raw initialization data is not retained or logged. This
 * helper never sees media sample payloads, manifest URLs, DRM init data, licence responses, KIDs
 * or IV values.
 */
internal object HevcFeedProfileProbe {
    data class Snapshot(
        val label: String,
        val group: String,
        val deliveryType: String,
        val observedMediaChunkDurationsMs: List<Long>,
        val summary: String,
        val mime: String?,
        val codecs: String?,
        val width: Int,
        val height: Int,
        val frameRate: Float,
        val parameterSetFingerprint: String?,
        val parsedParameterCounts: String,
        val parsedVps: List<HevcParameterSets.VpsMetadata>,
        val parsedSps: List<HevcParameterSets.SpsMetadata>,
        val parsedPps: List<HevcParameterSets.PpsMetadata>,
        val drmSecurityLevel: String?,
        val widevineKeysLoaded: Boolean,
        val videoDecoderName: String?,
        val firstFrameRendered: Boolean
    )

    fun summarize(trackType: Int, format: Format): String = inspect(trackType, format).summary

    fun inspect(trackType: Int, format: Format, label: String = "feed", group: String = "session"): Snapshot {
        val initializationData = format.initializationData
        val nals = initializationData.flatMap { csd ->
            runCatching { HevcBitstream.splitAnnexB(csd) }.getOrElse {
                listOfNotNull(
                    runCatching { HevcBitstream.parseNalHeader(csd) }.getOrNull()
                        ?.takeIf { nal -> nal.type in 32..34 }
                )
            }
        }.filter { it.type in 32..34 }

        val vpsUnits = nals.filter { it.type == 32 }
        val spsUnits = nals.filter { it.type == 33 }
        val ppsUnits = nals.filter { it.type == 34 }

        val vps = vpsUnits.mapNotNull { nal ->
            runCatching { HevcParameterSets.parseVps(nal.bytes) }.getOrNull()
        }
        val sps = spsUnits.mapNotNull { nal ->
            runCatching { HevcParameterSets.parseSps(nal.bytes) }.getOrNull()
        }
        val spsById = sps.associateBy { it.spsId }
        val pps = ppsUnits.mapNotNull { nal ->
            val preliminary = runCatching { HevcParameterSets.parsePps(nal.bytes) }.getOrNull()
                ?: return@mapNotNull null
            val referenceSps = spsById[preliminary.spsId]
            if (referenceSps == null) preliminary
            else runCatching { HevcParameterSets.parsePps(nal.bytes, referenceSps) }.getOrDefault(preliminary)
        }

        val vpsSummary = vps.joinToString("|") {
            "id=${it.vpsId},layers=${it.maxLayers},subLayers=${it.maxSubLayers}," +
                "ptl=${it.profileTierLevel.profileIdc}/${it.profileTierLevel.levelIdc}," +
                "ordering=${it.subLayerOrderingInfoPresent},maxDPB=${it.maxDecPicBuffering}," +
                "timing=${it.timingInfoPresent},hrd=${it.hrdParameters.size},extension=${it.extensionPresent}"
        }.ifEmpty { "unavailable" }
        val spsSummary = sps.joinToString("|") {
            "id=${it.spsId},vps=${it.vpsId},profile=${it.profileTierLevel.profileIdc}/${it.profileTierLevel.levelIdc}," +
                "size=${it.width}x${it.height},display=${it.displayWidth}x${it.displayHeight}," +
                "chroma=${it.chromaFormatIdc},bitDepth=${it.bitDepthLuma}/${it.bitDepthChroma}," +
                "pocBits=${it.log2MaxPicOrderCntLsb},ctb=${it.ctbSize},ctbGrid=${it.picWidthInCtbs}x${it.picHeightInCtbs}," +
                "vui=${it.vuiParametersPresent},spsExtension=${it.spsExtensionPresent}"
        }.ifEmpty { "unavailable" }
        val ppsSummary = pps.joinToString("|") {
            "id=${it.ppsId},sps=${it.spsId},tiles=${it.tilesEnabled},tileGrid=${it.numTileColumns}x${it.numTileRows}," +
                "tileColumns=${it.tileColumnWidthsInCtbs},tileRows=${it.tileRowHeightsInCtbs}," +
                "entropySync=${it.entropyCodingSyncEnabled},dependentSlices=${it.dependentSliceSegmentsEnabled}," +
                "weighted=${it.weightedPredictionEnabled}/${it.weightedBipredictionEnabled}," +
                "deblockOverride=${it.deblockingFilterOverrideEnabled},listModification=${it.listsModificationPresent}," +
                "sliceHeaderExtension=${it.sliceSegmentHeaderExtensionPresent},extension=${it.extensionPresent}"
        }.ifEmpty { "unavailable" }

        val parsedCounts = "${vps.size}/${sps.size}/${pps.size}"
        // Hash individual parameter NALs, then hash their sorted fingerprints. The result is
        // independent of CSD array/NAL ordering while preserving duplicate counts.
        val unitFingerprints = nals.map { nal ->
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(nal.type.toByte())
            digest.update(nal.bytes)
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }.sorted()
        val fingerprint = unitFingerprints.takeIf { it.isNotEmpty() }?.let { units ->
            val digest = MessageDigest.getInstance("SHA-256")
            units.forEach { unit ->
                digest.update(unit.toByteArray(Charsets.US_ASCII))
                digest.update(0.toByte())
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        val summary = "F1_HEVC_PROFILE label=$label trackType=$trackType mime=${format.sampleMimeType ?: "?"} " +
            "codecs=${format.codecs ?: "?"} size=${format.width}x${format.height} " +
            "frameRate=${format.frameRate} initializationDataCount=${initializationData.size} " +
            "parameterNalCounts=${vpsUnits.size}/${spsUnits.size}/${ppsUnits.size} " +
            "parsedParameterCounts=$parsedCounts parameterSetFingerprint=${fingerprint ?: "unavailable"} " +
            "vps={$vpsSummary} sps={$spsSummary} pps={$ppsSummary}"
        return Snapshot(
            label = label,
            group = group,
            deliveryType = "UNKNOWN",
            observedMediaChunkDurationsMs = emptyList(),
            summary = summary,
            mime = format.sampleMimeType,
            codecs = format.codecs,
            width = format.width,
            height = format.height,
            frameRate = format.frameRate,
            parameterSetFingerprint = fingerprint,
            parsedParameterCounts = parsedCounts,
            parsedVps = vps,
            parsedSps = sps,
            parsedPps = pps,
            drmSecurityLevel = null,
            widevineKeysLoaded = false,
            videoDecoderName = null,
            firstFrameRendered = false
        )
    }
}


/**
 * Volatile, in-memory comparison of the last two authorised feed observations from one F1 content
 * session. It holds sanitised codec metadata only. It is not a CMAF/encryption compatibility or
 * secure-surface feasibility result.
 */
internal object HevcFeedProfileCapture {
    private data class FeedProgress(
        var drmSecurityLevel: String? = null,
        var keysLoaded: Boolean = false,
        var videoDecoderName: String? = null,
        var firstFrameRendered: Boolean = false,
        var deliveryType: String = "UNKNOWN",
        val chunkDurationsMs: MutableList<Long> = mutableListOf()
    )

    private val profiles = linkedMapOf<String, HevcFeedProfileProbe.Snapshot>()
    private val feedProgress = linkedMapOf<String, FeedProgress>()
    private data class CmafFeedProgress(
        val videoTracks: MutableMap<Long, CmafBoxInspector.TrackMetadata> = linkedMapOf(),
        val latestFragments: MutableMap<Long, CmafBoxInspector.FragmentMetadata> = linkedMapOf(),
        var initSegmentsObserved: Int = 0,
        var mediaFragmentsObserved: Int = 0,
        var parseFailures: Int = 0,
        var overLimitSegments: Int = 0
    )
    private val cmafProgress = linkedMapOf<String, CmafFeedProgress>()
    private var activeGroup: String? = null
    private var tileTestLabels: List<String> = emptyList()
    var diagnostic by mutableStateOf("HEVC_PROFILE_WAITING_FOR_FEED_A_AND_FEED_B")
        private set

    private fun selectGroup(group: String) {
        if (activeGroup != group) {
            profiles.clear()
            feedProgress.clear()
            cmafProgress.clear()
            tileTestLabels = emptyList()
            activeGroup = group
        }
    }

    @Synchronized
    fun registerFeed(group: String, label: String) {
        selectGroup(group)
        feedProgress.getOrPut(label) { FeedProgress() }
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun selectTileTestFeeds(group: String, labels: List<String>) {
        selectGroup(group)
        tileTestLabels = labels.distinct().take(2)
        tileTestLabels.forEach { label ->
            feedProgress.getOrPut(label) { FeedProgress() }
            cmafProgress[label]?.let {
                it.latestFragments.clear()
                it.mediaFragmentsObserved = 0
            }
        }
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun clearTileTestSelection() {
        tileTestLabels = emptyList()
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun shouldCaptureCmafInit(group: String, label: String): Boolean =
        activeGroup == group && label in feedProgress

    @Synchronized
    fun shouldCaptureCmaf(group: String, label: String): Boolean =
        activeGroup == group && tileTestLabels.size == 2 && label in tileTestLabels

    @Synchronized
    fun recordCmafParseFailure(group: String, label: String) {
        if (!shouldCaptureCmafInit(group, label)) return
        cmafProgress.getOrPut(label) { CmafFeedProgress() }.parseFailures++
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun recordCmafCaptureSkipped(group: String, label: String) {
        if (!shouldCaptureCmafInit(group, label)) return
        cmafProgress.getOrPut(label) { CmafFeedProgress() }.overLimitSegments++
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun recordCmafReport(group: String, label: String, report: CmafBoxInspector.Report) {
        if (!shouldCaptureCmafInit(group, label)) return
        val progress = cmafProgress.getOrPut(label) { CmafFeedProgress() }
        val videoTracks = report.tracks.filter { track ->
            track.handler == "vide" && (track.hevcConfiguration != null ||
                track.sampleEntry in setOf("hvc1", "hev1", "encv", "dvhe", "dvh1"))
        }
        if (videoTracks.isNotEmpty()) {
            progress.initSegmentsObserved++
            videoTracks.forEach { track ->
                track.trackId?.let { progress.videoTracks[it] = track }
            }
        }
        val selectedForTileTest = shouldCaptureCmaf(group, label)
        val fragments = if (selectedForTileTest) report.fragments.filter { fragment ->
            fragment.trackId in progress.videoTracks
        } else emptyList()
        if (fragments.isNotEmpty()) {
            progress.mediaFragmentsObserved += fragments.size
            fragments.forEach { progress.latestFragments[it.trackId] = it }
        }
        if (report.errors.isNotEmpty()) {
            progress.parseFailures += report.errors.size.coerceAtMost(8)
        }
        while (progress.latestFragments.size > 4) {
            progress.latestFragments.remove(progress.latestFragments.keys.first())
        }
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun record(snapshot: HevcFeedProfileProbe.Snapshot) {
        selectGroup(snapshot.group)
        val progress = feedProgress.getOrPut(snapshot.label) { FeedProgress() }
        progress.drmSecurityLevel = snapshot.drmSecurityLevel ?: progress.drmSecurityLevel
        progress.keysLoaded = progress.keysLoaded || snapshot.widevineKeysLoaded
        progress.videoDecoderName = snapshot.videoDecoderName ?: progress.videoDecoderName
        progress.firstFrameRendered = progress.firstFrameRendered || snapshot.firstFrameRendered
        if (snapshot.deliveryType != "UNKNOWN") progress.deliveryType = snapshot.deliveryType
        if (snapshot.observedMediaChunkDurationsMs.isNotEmpty()) {
            progress.chunkDurationsMs.clear()
            progress.chunkDurationsMs.addAll(snapshot.observedMediaChunkDurationsMs.takeLast(8))
        }
        val enriched = snapshot.copy(
            deliveryType = progress.deliveryType,
            observedMediaChunkDurationsMs = progress.chunkDurationsMs.toList(),
            drmSecurityLevel = progress.drmSecurityLevel,
            widevineKeysLoaded = progress.keysLoaded,
            videoDecoderName = progress.videoDecoderName,
            firstFrameRendered = progress.firstFrameRendered
        )
        profiles.remove(snapshot.label)
        profiles[snapshot.label] = enriched
        while (profiles.size > 8) profiles.remove(profiles.keys.first())
        while (feedProgress.size > 4) feedProgress.remove(feedProgress.keys.first())
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun updateMediaChunkDuration(group: String, label: String, deliveryType: String, durationMs: Long) {
        if (activeGroup != group || durationMs !in 1_000L..12_000L) return
        val progress = feedProgress.getOrPut(label) { FeedProgress() }
        progress.deliveryType = deliveryType
        progress.chunkDurationsMs.add(durationMs)
        while (progress.chunkDurationsMs.size > 8) progress.chunkDurationsMs.removeAt(0)
        profiles[label]?.let { current ->
            profiles[label] = current.copy(
                deliveryType = progress.deliveryType,
                observedMediaChunkDurationsMs = progress.chunkDurationsMs.toList()
            )
        }
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun updateVideoDecoder(group: String, label: String, decoderName: String) {
        if (activeGroup != group) return
        val safeName = decoderName.filter { it.isLetterOrDigit() || it in "._-" }.take(96)
        if (safeName.isBlank()) return
        val progress = feedProgress.getOrPut(label) { FeedProgress() }
        progress.videoDecoderName = safeName
        profiles[label]?.let { profiles[label] = it.copy(videoDecoderName = safeName) }
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun updateFirstFrameRendered(group: String, label: String) {
        if (activeGroup != group) return
        val progress = feedProgress.getOrPut(label) { FeedProgress() }
        progress.firstFrameRendered = true
        profiles[label]?.let { profiles[label] = it.copy(firstFrameRendered = true) }
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun updateDrm(group: String, label: String, securityLevel: String, keysLoaded: Boolean) {
        if (activeGroup != group) return
        val progress = feedProgress.getOrPut(label) { FeedProgress() }
        progress.drmSecurityLevel = securityLevel.takeIf { it.isNotBlank() } ?: progress.drmSecurityLevel
        progress.keysLoaded = progress.keysLoaded || keysLoaded
        profiles[label]?.let {
            profiles[label] = it.copy(
                drmSecurityLevel = progress.drmSecurityLevel,
                widevineKeysLoaded = progress.keysLoaded
            )
        }
        diagnostic = buildDiagnostic()
    }

    private fun progressLine(label: String): String {
        val progress = feedProgress.getValue(label)
        val state = if (label in profiles) "FORMAT_CAPTURED" else "WAITING_FOR_VIDEO_INPUT_FORMAT"
        return "$label: $state; DRM=${progress.drmSecurityLevel ?: "not-observed"}; " +
            "keysLoadedEventObserved=${progress.keysLoaded}; decoder=${progress.videoDecoderName ?: "not-observed"}; " +
            "firstFrameRendered=${progress.firstFrameRendered}"
    }

    private fun compareParsedParameterSets(
        first: HevcFeedProfileProbe.Snapshot,
        second: HevcFeedProfileProbe.Snapshot
    ): String {
        if (first.parsedVps.isEmpty() || first.parsedSps.isEmpty() || first.parsedPps.isEmpty() ||
            second.parsedVps.isEmpty() || second.parsedSps.isEmpty() || second.parsedPps.isEmpty()) {
            return "INCOMPLETE: one or more VPS/SPS/PPS sets could not be parsed"
        }
        val reasons = mutableListOf<String>()
        val firstVps = first.parsedVps.associateBy { it.vpsId }
        val secondVps = second.parsedVps.associateBy { it.vpsId }
        val firstSps = first.parsedSps.associateBy { it.spsId }
        val secondSps = second.parsedSps.associateBy { it.spsId }
        val firstPps = first.parsedPps.associateBy { it.ppsId }
        val secondPps = second.parsedPps.associateBy { it.ppsId }

        if (firstVps.size != first.parsedVps.size || secondVps.size != second.parsedVps.size ||
            firstSps.size != first.parsedSps.size || secondSps.size != second.parsedSps.size ||
            firstPps.size != first.parsedPps.size || secondPps.size != second.parsedPps.size) {
            reasons += "duplicate parameter-set IDs are present"
        }
        if (firstVps != secondVps) reasons += "VPS IDs or metadata differ"
        if (firstSps != secondSps) reasons += "SPS IDs or metadata differ"
        if (firstPps != secondPps) reasons += "PPS IDs or metadata differ"

        firstPps.forEach { (ppsId, firstPpsMetadata) ->
            val secondPpsMetadata = secondPps[ppsId] ?: return@forEach
            val firstSpsMetadata = firstSps[firstPpsMetadata.spsId]
            val secondSpsMetadata = secondSps[secondPpsMetadata.spsId]
            if (firstSpsMetadata == null || secondSpsMetadata == null) {
                reasons += "PPS $ppsId references a missing SPS"
                return@forEach
            }
            val firstVpsMetadata = firstVps[firstSpsMetadata.vpsId]
            val secondVpsMetadata = secondVps[secondSpsMetadata.vpsId]
            if (firstVpsMetadata == null || secondVpsMetadata == null) {
                reasons += "SPS ${firstSpsMetadata.spsId} references a missing VPS"
                return@forEach
            }
            val gate = HevcParameterSetCompatibility.compare(
                firstVpsMetadata, firstSpsMetadata, firstPpsMetadata,
                secondVpsMetadata, secondSpsMetadata, secondPpsMetadata
            )
            if (!gate.compatible) reasons += gate.reasons.map { "PPS $ppsId: $it" }
        }
        return if (reasons.isEmpty()) "PASS" else "REJECT: ${reasons.distinct().joinToString("; ")}"
    }

    private fun tileMetadataLines(profile: HevcFeedProfileProbe.Snapshot): List<String> {
        if (profile.parsedPps.isEmpty()) {
            val sps = profile.parsedSps.singleOrNull()
            return listOf(
                "${profile.label}: HEVC_TILE_METADATA pps=unavailable tilesEnabled=unavailable " +
                    "numTileColumns=unavailable numTileRows=unavailable " +
                    "ctbPictureWidthInCtbs=${sps?.picWidthInCtbs ?: "unavailable"} " +
                    "ctbPictureHeightInCtbs=${sps?.picHeightInCtbs ?: "unavailable"} " +
                    "tileColumnWidthsInCtbs=unavailable tileRowHeightsInCtbs=unavailable " +
                    "deblockingFilterOverrideEnabled=unavailable"
            )
        }

        return profile.parsedPps.sortedBy { it.ppsId }.map { pps ->
            val matchingSps = profile.parsedSps.filter { it.spsId == pps.spsId }
            val sps = matchingSps.singleOrNull()
            "${profile.label}: HEVC_TILE_METADATA ppsId=${pps.ppsId} spsId=${pps.spsId} " +
                "tilesEnabled=${pps.tilesEnabled} numTileColumns=${pps.numTileColumns} " +
                "numTileRows=${pps.numTileRows} " +
                "ctbPictureWidthInCtbs=${sps?.picWidthInCtbs ?: "unavailable"} " +
                "ctbPictureHeightInCtbs=${sps?.picHeightInCtbs ?: "unavailable"} " +
                "tileColumnWidthsInCtbs=${pps.tileColumnWidthsInCtbs ?: "unavailable"} " +
                "tileRowHeightsInCtbs=${pps.tileRowHeightsInCtbs ?: "unavailable"} " +
                "deblockingFilterOverrideEnabled=${pps.deblockingFilterOverrideEnabled} " +
                "deblockingFilterControlPresent=${pps.deblockingFilterControlPresent}" +
                if (matchingSps.size > 1) " spsMetadata=ambiguous" else ""
        }
    }

    /**
     * Compares every parsed PPS field, keyed by PPS id. This is parsed metadata equality,
     * not a byte-for-byte comparison of the original PPS NAL units.
     */
    private fun parsedPpsStructuresIdentical(
        first: HevcFeedProfileProbe.Snapshot,
        second: HevcFeedProfileProbe.Snapshot
    ): String {
        if (first.parsedPps.isEmpty() || second.parsedPps.isEmpty()) {
            return "UNKNOWN (one or both feeds have no parsed PPS)"
        }
        val firstById = first.parsedPps.associateBy { it.ppsId }
        val secondById = second.parsedPps.associateBy { it.ppsId }
        if (firstById.size != first.parsedPps.size || secondById.size != second.parsedPps.size) {
            return "UNKNOWN (duplicate PPS ids)"
        }
        return (firstById == secondById).toString()
    }

    private fun buildDiagnostic(): String {
        val profile = buildHevcProfileDiagnostic()
        val cmaf = buildCmafDiagnostic()
        return if (cmaf.isBlank()) profile else "$profile\n\n$cmaf"
    }

    private fun buildHevcProfileDiagnostic(): String {
        val captured = if (tileTestLabels.size == 2) {
            val selected = tileTestLabels.mapNotNull(profiles::get)
            if (selected.size < 2) {
                return (listOf("TILE_TEST_WAITING_FOR_SELECTED_FEED_FORMATS") +
                    tileTestLabels.map { label ->
                        feedProgress.getOrPut(label) { FeedProgress() }
                        progressLine(label)
                    }).joinToString("\n")
            }
            selected
        } else profiles.values.toList()
        if (captured.isEmpty()) {
            if (feedProgress.isEmpty()) return "HEVC_PROFILE_WAITING_FOR_FEED_A_AND_FEED_B"
            return (listOf("HEVC_PROFILE_WAITING_FOR_VIDEO_INPUT_FORMAT") +
                feedProgress.keys.map(::progressLine)).joinToString("\n")
        }
        if (captured.size == 1) {
            val first = captured.single()
            val pending = feedProgress.keys.filter { it !in profiles }
            return (listOf(
                "HEVC_PROFILE_CAPTURED",
                "${first.label}: ${first.mime ?: "?"} ${first.codecs ?: "?"} ${first.width}x${first.height}@${first.frameRate}fps; " +
                    "delivery=${first.deliveryType}; chunksMs=${first.observedMediaChunkDurationsMs}; " +
                    "VPS/SPS/PPS=${first.parsedParameterCounts}; fingerprint=${first.parameterSetFingerprint ?: "unavailable"}"
            ) + tileMetadataLines(first) + listOf(
                progressLine(first.label),
                if (pending.isEmpty()) "HEVC_PROFILE_WAITING_FOR_FEED_B"
                else pending.joinToString("\n", prefix = "HEVC_PROFILE_WAITING_FOR_VIDEO_INPUT_FORMAT\n") { progressLine(it) }
            )).joinToString("\n")
        }
        val first = captured[0]
        val second = captured[1]
        val reasons = buildList {
            if (first.mime != second.mime) add("MIME differs")
            if (first.codecs != second.codecs) add("codec string differs")
            if (first.width <= 0 || first.height <= 0 || second.width <= 0 || second.height <= 0) {
                add("video dimensions unavailable")
            } else if (first.width != second.width || first.height != second.height) {
                add("dimensions differ")
            }
            if (first.mime?.equals("video/hevc", ignoreCase = true) != true ||
                second.mime?.equals("video/hevc", ignoreCase = true) != true) {
                add("one or both tracks are not identified as HEVC")
            }
            if (first.codecs.isNullOrBlank() || second.codecs.isNullOrBlank()) add("codec string unavailable")
            val firstCounts = first.parsedParameterCounts.split('/').mapNotNull { it.toIntOrNull() }
            val secondCounts = second.parsedParameterCounts.split('/').mapNotNull { it.toIntOrNull() }
            if (firstCounts.size != 3 || secondCounts.size != 3 ||
                firstCounts.any { it <= 0 } || secondCounts.any { it <= 0 }) {
                add("VPS/SPS/PPS parsing is incomplete")
            }
            if (first.parameterSetFingerprint == null || second.parameterSetFingerprint == null) {
                add("parameter-set fingerprint unavailable")
            } else if (first.parameterSetFingerprint != second.parameterSetFingerprint) {
                add("VPS/SPS/PPS fingerprint differs")
            }
            if (first.parsedParameterCounts != second.parsedParameterCounts) add("parsed parameter counts differ")
            if (first.frameRate > 0f && second.frameRate > 0f && first.frameRate != second.frameRate) {
                add("reported frame rates differ")
            }
        }
        val result = if (reasons.isEmpty()) "HEVC_FORMAT_MATCH" else "HEVC_FORMAT_MISMATCH"
        fun drm(profile: HevcFeedProfileProbe.Snapshot) =
            "DRM=${profile.drmSecurityLevel ?: "not-observed"},keysLoadedEventObserved=${profile.widevineKeysLoaded}," +
                "decoder=${profile.videoDecoderName ?: "not-observed"},firstFrameRendered=${profile.firstFrameRendered}"
        val parameterGate = compareParsedParameterSets(first, second)
        return buildList {
            add(result)
            add("HEVC_TILE_ASSEMBLER_GATE=$parameterGate")
            add(
                "${first.label}: ${first.mime ?: "?"} ${first.codecs ?: "?"} ${first.width}x${first.height}@${first.frameRate}fps; " +
                    "VPS/SPS/PPS=${first.parsedParameterCounts}; fingerprint=${first.parameterSetFingerprint ?: "unavailable"}; " +
                    "delivery=${first.deliveryType}; chunksMs=${first.observedMediaChunkDurationsMs}"
            )
            addAll(tileMetadataLines(first))
            add("${first.label}: ${drm(first)}")
            add(
                "${second.label}: ${second.mime ?: "?"} ${second.codecs ?: "?"} ${second.width}x${second.height}@${second.frameRate}fps; " +
                    "VPS/SPS/PPS=${second.parsedParameterCounts}; fingerprint=${second.parameterSetFingerprint ?: "unavailable"}; " +
                    "delivery=${second.deliveryType}; chunksMs=${second.observedMediaChunkDurationsMs}"
            )
            addAll(tileMetadataLines(second))
            add("${second.label}: ${drm(second)}")
            add("HEVC_TILE_PARSED_PPS_STRUCTURES_IDENTICAL=${parsedPpsStructuresIdentical(first, second)}")
            add(
                if (reasons.isEmpty()) "CMAF timing, encryption layout and secure-surface feasibility remain unverified."
                else "Mismatch reasons: ${reasons.joinToString("; ")}. CMAF timing, encryption layout and secure-surface feasibility remain unverified."
            )
        }.joinToString("\n")
    }
    /**
     * Runtime CMAF observations are metadata-only. Sample payloads are not parsed; for Widevine
     * content the KID and IV values are never read or displayed, and no protected bytes are saved.
     */
    private fun buildCmafDiagnostic(): String {
        if (tileTestLabels.size != 2) return ""
        val states = tileTestLabels.map { label -> label to cmafProgress[label] }
        val lines = mutableListOf(
            "F1_CMAF_READ_ONLY_INSPECTOR=ACTIVE",
            "CMAF_CAPTURE_SCOPE=two selected feeds; bounded in-memory ISO-BMFF metadata only"
        )
        states.forEach { (label, state) ->
            val track = state?.videoTracks?.values?.firstOrNull()
            val fragment = track?.trackId?.let { state.latestFragments[it] }
            if (track == null) {
                lines += "$label: CMAF_INIT=WAITING; initSegments=${state?.initSegmentsObserved ?: 0}; mediaFragments=${state?.mediaFragmentsObserved ?: 0}; parseFailures=${state?.parseFailures ?: 0}; overLimit=${state?.overLimitSegments ?: 0}"
            } else {
                val config = track.hevcConfiguration
                val pps = config?.pps?.singleOrNull()
                val protection = track.trackEncryption
                lines += "$label: trackId=${track.trackId}; codec=${track.originalFormat ?: track.sampleEntry ?: "unknown"}; ${track.width ?: 0}x${track.height ?: 0}; timescale=${track.timescale ?: "unknown"}; scheme=${track.protectionScheme ?: "none"}; encrypted=${track.encrypted}"
                lines += "$label: hvcC VPS/SPS/PPS=${config?.vps?.size ?: 0}/${config?.sps?.size ?: 0}/${config?.pps?.size ?: 0}; tileGrid=${pps?.numTileColumns ?: "unknown"}x${pps?.numTileRows ?: "unknown"}; tileColumnsCTB=${pps?.tileColumnWidthsInCtbs ?: "unknown"}"
                lines += "$label: tencPresent=${protection != null}; protected=${protection?.defaultIsProtected ?: "unknown"}; perSampleIvSize=${protection?.perSampleIvSize ?: "unknown"}; pattern=${protection?.cryptByteBlock ?: 0}/${protection?.skipByteBlock ?: 0}; initSegments=${state?.initSegmentsObserved ?: 0}; mediaFragments=${state?.mediaFragmentsObserved ?: 0}"
                if (fragment != null) {
                    val firstSample = fragment.sampleTimings.firstOrNull()
                    lines += "$label: fragmentSamples=${fragment.sampleCount}; baseDecodeTicks=${fragment.baseDecodeTimeTicks ?: "unknown"}; firstDts=${firstSample?.decodeTimeTicks ?: "unknown"}; firstPts=${firstSample?.presentationTimeTicks ?: "unknown"}; firstDurationTicks=${firstSample?.durationTicks ?: "unknown"}; timingComplete=${fragment.timingComplete}"
                    lines += "$label: senc=${fragment.sampleEncryptionBoxPresent}; subsample=${fragment.subsampleEncryptionSignalled ?: "unknown"}; encryptionStructureComplete=${fragment.sampleEncryptionStructureComplete}; rangesCoverSamples=${fragment.sampleEncryptionRangesCoverSamples}; saizSaioComplete=${fragment.sampleAuxiliaryInfoStructureComplete}; auxiliaryRecordsComplete=${fragment.sampleAuxiliaryPayloadStructureComplete}; seigComplete=${fragment.sampleGroupEncryptionStructureComplete}"
                } else {
                    lines += "$label: CMAF_MEDIA_FRAGMENT=WAITING"
                }
                if ((state?.parseFailures ?: 0) > 0 || (state?.overLimitSegments ?: 0) > 0) {
                    lines += "$label: parseFailures=${state?.parseFailures ?: 0}; overLimit=${state?.overLimitSegments ?: 0}"
                }
            }
        }

        val firstTrack = states.firstOrNull()?.second?.videoTracks?.values?.firstOrNull()
        val secondTrack = states.getOrNull(1)?.second?.videoTracks?.values?.firstOrNull()
        val firstFragment = firstTrack?.trackId?.let { states.firstOrNull()?.second?.latestFragments?.get(it) }
        val secondFragment = secondTrack?.trackId?.let { states.getOrNull(1)?.second?.latestFragments?.get(it) }
        if (firstTrack != null && secondTrack != null) {
            val firstTiming = firstFragment?.sampleTimings?.mapNotNull { it.durationTicks }?.distinct()?.singleOrNull()
            val secondTiming = secondFragment?.sampleTimings?.mapNotNull { it.durationTicks }?.distinct()?.singleOrNull()
            val trackGate = HevcFeedCompatibility.compare(
                HevcFeedCompatibility.Profile(
                    firstTrack.originalFormat ?: firstTrack.sampleEntry,
                    firstTrack.width, firstTrack.height, firstTrack.timescale, firstTiming,
                    firstTrack.protectionScheme
                ),
                HevcFeedCompatibility.Profile(
                    secondTrack.originalFormat ?: secondTrack.sampleEntry,
                    secondTrack.width, secondTrack.height, secondTrack.timescale, secondTiming,
                    secondTrack.protectionScheme
                )
            )
            lines += "CMAF_TRACK_PROFILE_GATE=${if (trackGate.compatible) "MATCH" else "NOT_COMPATIBLE"}; reasons=${trackGate.reasons.ifEmpty { listOf("none") }.joinToString("|")}"

            val firstConfig = firstTrack.hevcConfiguration
            val secondConfig = secondTrack.hevcConfiguration
            val tileGate = HevcParameterSetCompatibility.compare(
                firstConfig?.vps?.singleOrNull(), firstConfig?.sps?.singleOrNull(), firstConfig?.pps?.singleOrNull(),
                secondConfig?.vps?.singleOrNull(), secondConfig?.sps?.singleOrNull(), secondConfig?.pps?.singleOrNull()
            )
            lines += "CMAF_HEVC_TILE_CONFIG_GATE=${if (tileGate.compatible) "MATCH" else "NOT_COMPATIBLE"}; reasons=${tileGate.reasons.ifEmpty { listOf("none") }.joinToString("|")}"
        } else {
            lines += "CMAF_TRACK_COMPARISON=WAITING_FOR_BOTH_VIDEO_INIT_SEGMENTS"
        }

        if (firstTrack != null && secondTrack != null && firstFragment != null && secondFragment != null) {
            val fragmentGate = HevcFragmentCompatibility.compare(firstTrack, firstFragment, secondTrack, secondFragment)
            lines += "CMAF_FRAGMENT_TIMING_ENCRYPTION_GATE=${if (fragmentGate.compatible) "MATCH" else "NOT_COMPATIBLE"}; reasons=${fragmentGate.reasons.ifEmpty { listOf("none") }.joinToString("|")}"
        } else {
            lines += "CMAF_FRAGMENT_COMPARISON=WAITING_FOR_BOTH_VIDEO_MEDIA_FRAGMENTS"
        }
        lines += "HEVC_SLICE_HEADER_ANALYSIS=NOT_READ; protected media sample bytes are not inspected"
        lines += "DRM_SAFETY=KID values and IV values are never read or displayed; no decryption is attempted"
        return lines.joinToString("\n")
    }

}
