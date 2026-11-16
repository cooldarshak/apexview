package app.apexview.tiled

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Builds the one-representation DASH package that lets Media3 consume the composed CMAF output.
 *
 * This is the Android equivalent of the Tizen path that publishes one grid MPD, one init segment,
 * and the generated grid media segments. It intentionally accepts clear CMAF only: the current
 * Kotlin composer rejects protected samples, and this adapter never manufactures DRM signalling.
 */
internal object HevcComposedDashPackageBuilder {
    data class Package(
        val manifest: String,
        val resources: Map<String, ByteArray>,
        val width: Int,
        val height: Int,
        val sampleCount: Int,
        val fragmentCount: Int,
        val durationTicks: Long,
        val timescale: Long
    )

    private data class SegmentTiming(
        val startTicks: Long,
        val durationTicks: Long,
        val firstSampleFlags: Long?
    )

    fun build(initializationSegment: ByteArray, mediaFragments: List<ByteArray>): Package {
        require(initializationSegment.isNotEmpty()) { "Composed CMAF init segment is empty" }
        require(mediaFragments.isNotEmpty()) { "Composed CMAF has no media fragments" }

        val initReport = CmafBoxInspector.inspect(initializationSegment)
        require(initReport.errors.isEmpty()) {
            "Composed CMAF init segment is invalid: ${initReport.errors.joinToString("; ")}"
        }
        require(initReport.initializationSegmentPresent && initReport.tracks.size == 1) {
            "Composed CMAF must contain exactly one initialized track"
        }
        val track = initReport.tracks.single()
        require(track.handler == "vide" && (track.sampleEntry == "hvc1" || track.sampleEntry == "hev1")) {
            "Composed CMAF must contain one HEVC video track"
        }
        require(!track.encrypted && track.protectionScheme == null && track.trackEncryption == null &&
            !track.sampleEncryptionBoxPresent && !track.sampleAuxiliarySizesPresent &&
            !track.sampleAuxiliaryOffsetsPresent && !track.sampleGroupDescriptionPresent &&
            !track.sampleToGroupPresent) {
            "Media3 clear-CMAF adapter rejects protected tracks"
        }
        val trackId = requireNotNull(track.trackId) { "Composed CMAF track ID is missing" }
        val timescale = requireNotNull(track.timescale)
            .takeIf { it > 0L } ?: error("Composed CMAF media timescale is invalid")
        val width = requireNotNull(track.width).takeIf { it > 0 }
            ?: error("Composed CMAF display width is missing")
        val height = requireNotNull(track.height).takeIf { it > 0 }
            ?: error("Composed CMAF display height is missing")
        val config = requireNotNull(track.hevcConfiguration) {
            "Composed CMAF hvcC configuration is missing"
        }
        require(config.parameterSetErrors.isEmpty() && config.vps.isNotEmpty() &&
            config.sps.isNotEmpty() && config.pps.isNotEmpty()) {
            "Composed CMAF HEVC parameter sets are incomplete"
        }

        val segmentTimings = mutableListOf<SegmentTiming>()
        var sampleCount = 0
        var previousEnd: Long? = null
        mediaFragments.forEachIndexed { index, bytes ->
            val report = CmafBoxInspector.inspect(bytes, priorInitialization = initReport)
            require(report.errors.isEmpty()) {
                "Composed CMAF fragment $index is invalid: ${report.errors.joinToString("; ")}"
            }
            require(report.fragments.size == 1) {
                "Composed CMAF fragment $index must contain exactly one track fragment"
            }
            val fragment = report.fragments.single()
            require(fragment.trackId == trackId && fragment.timescale == timescale) {
                "Composed CMAF fragment $index does not match its init segment"
            }
            require(!fragment.sampleEncryptionBoxPresent && !fragment.sampleAuxiliarySizesPresent &&
                !fragment.sampleAuxiliaryOffsetsPresent && !fragment.sampleGroupDescriptionPresent &&
                !fragment.sampleToGroupPresent && !fragment.sampleGroupEncryptionPresent) {
                "Media3 clear-CMAF adapter rejects fragment encryption metadata"
            }
            require(fragment.timingComplete && fragment.sampleCount > 0 &&
                fragment.sampleTimings.size == fragment.sampleCount) {
                "Composed CMAF fragment $index has incomplete sample timing"
            }

            val timedSamples = fragment.sampleTimings.mapIndexed { sampleIndex, timing ->
                val presentation = requireNotNull(timing.presentationTimeTicks) {
                    "Composed CMAF fragment $index sample $sampleIndex has no presentation time"
                }
                val duration = requireNotNull(timing.durationTicks)
                    .takeIf { it > 0L }
                    ?: error("Composed CMAF fragment $index sample $sampleIndex has invalid duration")
                presentation to Math.addExact(presentation, duration)
            }
            val start = timedSamples.minOf { it.first }
            val end = timedSamples.maxOf { it.second }
            require(start >= 0L && end > start) {
                "Composed CMAF fragment $index has an invalid presentation interval"
            }
            previousEnd?.let { expectedStart ->
                require(start == expectedStart) {
                    "Composed CMAF fragment $index is not presentation-contiguous"
                }
            }
            previousEnd = end
            segmentTimings += SegmentTiming(
                startTicks = start,
                durationTicks = end - start,
                firstSampleFlags = fragment.sampleTimings.first().sampleFlags
            )
            sampleCount = Math.addExact(sampleCount, fragment.sampleCount)
        }

        val firstPresentationTime = segmentTimings.first().startTicks
        val presentationEnd = Math.addExact(
            segmentTimings.last().startTicks,
            segmentTimings.last().durationTicks
        )
        val presentationDuration = presentationEnd - firstPresentationTime
        require(presentationDuration > 0L) { "Composed CMAF presentation duration is invalid" }
        val byteCount = mediaFragments.fold(0L) { total, fragment ->
            Math.addExact(total, fragment.size.toLong())
        }
        val bandwidth = BigDecimal.valueOf(byteCount)
            .multiply(BigDecimal.valueOf(8L))
            .multiply(BigDecimal.valueOf(timescale))
            .divide(BigDecimal.valueOf(presentationDuration), 0, RoundingMode.CEILING)
            .longValueExact()
            .coerceAtLeast(1L)
        val startsWithSap = segmentTimings.first().firstSampleFlags
            ?.let { (it and NON_SYNC_SAMPLE_FLAG) == 0L } ?: false
        val durationText = isoDuration(presentationDuration, timescale)
        val segmentTimeline = segmentTimings.joinToString(separator = "") {
            """<S t="${it.startTicks}" d="${it.durationTicks}"/>"""
        }
        val segmentUrls = mediaFragments.indices.joinToString(separator = "") { index ->
            """<SegmentURL media="segment-$index.m4s"/>"""
        }
        val manifest = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" minBufferTime="PT0.2S" mediaPresentationDuration="PT$durationText">
  <Period id="composed-hevc" start="PT0S" duration="PT$durationText">
    <AdaptationSet id="1" contentType="video" mimeType="video/mp4" segmentAlignment="true" startWithSAP="${if (startsWithSap) 1 else 0}">
      <Representation id="hevc-grid" bandwidth="$bandwidth" codecs="${track.sampleEntry}" width="$width" height="$height">
        <SegmentList timescale="$timescale" presentationTimeOffset="$firstPresentationTime">
          <Initialization sourceURL="init.mp4"/>
          <SegmentTimeline>$segmentTimeline</SegmentTimeline>
          $segmentUrls
        </SegmentList>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>"""

        val resources = linkedMapOf<String, ByteArray>()
        resources["manifest.mpd"] = manifest.toByteArray(Charsets.UTF_8)
        resources["init.mp4"] = initializationSegment
        mediaFragments.forEachIndexed { index, fragment ->
            resources["segment-$index.m4s"] = fragment
        }
        return Package(
            manifest = manifest,
            resources = resources,
            width = width,
            height = height,
            sampleCount = sampleCount,
            fragmentCount = mediaFragments.size,
            durationTicks = presentationDuration,
            timescale = timescale
        )
    }

    private fun isoDuration(ticks: Long, timescale: Long): String =
        BigDecimal.valueOf(ticks)
            .divide(BigDecimal.valueOf(timescale), 6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    private const val NON_SYNC_SAMPLE_FLAG = 0x0001_0000L
}
