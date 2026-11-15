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
    private val profiles = linkedMapOf<String, HevcFeedProfileProbe.Snapshot>()
    private var activeGroup: String? = null
    var diagnostic by mutableStateOf("HEVC_PROFILE_WAITING_FOR_FEED_A_AND_FEED_B")
        private set

    @Synchronized
    fun record(snapshot: HevcFeedProfileProbe.Snapshot) {
        if (activeGroup != snapshot.group) {
            profiles.clear()
            activeGroup = snapshot.group
        }
        profiles.remove(snapshot.label)
        profiles[snapshot.label] = snapshot
        while (profiles.size > 2) profiles.remove(profiles.keys.first())
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun updateMediaChunkDuration(group: String, label: String, deliveryType: String, durationMs: Long) {
        if (activeGroup != group || durationMs !in 1_000L..12_000L) return
        val current = profiles[label] ?: return
        val durations = (current.observedMediaChunkDurationsMs + durationMs).takeLast(8)
        profiles[label] = current.copy(
            deliveryType = deliveryType,
            observedMediaChunkDurationsMs = durations
        )
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun updateVideoDecoder(group: String, label: String, decoderName: String) {
        if (activeGroup != group) return
        val current = profiles[label] ?: return
        val safeName = decoderName.filter { it.isLetterOrDigit() || it in "._-" }.take(96)
        if (safeName.isBlank()) return
        profiles[label] = current.copy(videoDecoderName = safeName)
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun updateFirstFrameRendered(group: String, label: String) {
        if (activeGroup != group) return
        val current = profiles[label] ?: return
        profiles[label] = current.copy(firstFrameRendered = true)
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun updateDrm(group: String, label: String, securityLevel: String, keysLoaded: Boolean) {
        if (activeGroup != group) return
        val current = profiles[label] ?: return
        profiles[label] = current.copy(
            drmSecurityLevel = securityLevel.takeIf { it.isNotBlank() },
            widevineKeysLoaded = current.widevineKeysLoaded || keysLoaded
        )
        diagnostic = buildDiagnostic()
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

    private fun buildDiagnostic(): String {
        if (profiles.isEmpty()) return "HEVC_PROFILE_WAITING_FOR_FEED_A"
        val captured = profiles.values.toList()
        if (captured.size == 1) {
            val first = captured.single()
            return listOf(
                "HEVC_PROFILE_CAPTURED",
                "${first.label}: ${first.mime ?: "?"} ${first.codecs ?: "?"} ${first.width}x${first.height}@${first.frameRate}fps; " +
                    "delivery=${first.deliveryType}; chunksMs=${first.observedMediaChunkDurationsMs}; " +
                    "VPS/SPS/PPS=${first.parsedParameterCounts}; fingerprint=${first.parameterSetFingerprint ?: "unavailable"}",
                "${first.label}: DRM=${first.drmSecurityLevel ?: "not-observed"}; keysLoaded=${first.widevineKeysLoaded}; " +
                    "decoder=${first.videoDecoderName ?: "not-observed"}; firstFrameRendered=${first.firstFrameRendered}"
            ).joinToString("\\n")
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
        val result = if (reasons.isEmpty()) "CODEC_PROFILE_MATCH" else "CODEC_PROFILE_MISMATCH"
        fun drm(profile: HevcFeedProfileProbe.Snapshot) =
            "DRM=${profile.drmSecurityLevel ?: "not-observed"},keysLoaded=${profile.widevineKeysLoaded}," +
                "decoder=${profile.videoDecoderName ?: "not-observed"},firstFrameRendered=${profile.firstFrameRendered}"
        val parameterGate = compareParsedParameterSets(first, second)
        return listOf(
            result,
            "HEVC_PARAMETER_SET_GATE=$parameterGate",
            "${first.label}: ${first.mime ?: "?"} ${first.codecs ?: "?"} ${first.width}x${first.height}@${first.frameRate}fps; " +
                "VPS/SPS/PPS=${first.parsedParameterCounts}; fingerprint=${first.parameterSetFingerprint ?: "unavailable"}; " +
                "delivery=${first.deliveryType}; chunksMs=${first.observedMediaChunkDurationsMs}",
            "${first.label}: ${drm(first)}",
            "${second.label}: ${second.mime ?: "?"} ${second.codecs ?: "?"} ${second.width}x${second.height}@${second.frameRate}fps; " +
                "VPS/SPS/PPS=${second.parsedParameterCounts}; fingerprint=${second.parameterSetFingerprint ?: "unavailable"}; " +
                "delivery=${second.deliveryType}; chunksMs=${second.observedMediaChunkDurationsMs}",
            "${second.label}: ${drm(second)}",
            if (reasons.isEmpty()) "CMAF timing, encryption layout and secure-surface feasibility remain unverified."
            else "Mismatch reasons: ${reasons.joinToString("; ")}. CMAF timing, encryption layout and secure-surface feasibility remain unverified."
        ).joinToString("\\n")
    }
}
