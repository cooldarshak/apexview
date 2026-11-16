package app.apexview.tiled

import java.math.BigInteger

/**
 * Validates two complete, ordered CMAF fragment sequences before any cross-feed composition.
 *
 * This is deliberately a metadata/identity gate. It does not read or merge sample payloads,
 * alter timestamps, bridge discontinuities, or make protected media composable.
 */
internal object HevcFragmentSequenceCompatibility {
    data class FeedSequence(
        val label: String,
        val track: CmafBoxInspector.TrackMetadata,
        val fragments: List<CmafBoxInspector.FragmentMetadata>,
        /** One parsed picture identity per HEVC sample, in decode order. */
        val pictures: List<HevcPictureIdentityCompatibility.PictureIdentity>
    )

    data class Result(
        val compatible: Boolean,
        val reasons: List<String>,
        val firstSampleCount: Int,
        val secondSampleCount: Int,
        val comparedFragmentCount: Int,
        val comparedSampleCount: Int
    )

    fun compare(first: FeedSequence, second: FeedSequence): Result {
        val reasons = mutableListOf<String>()
        val firstValidation = validateSequence(first)
        val secondValidation = validateSequence(second)
        reasons += firstValidation.map { "${first.label}: $it" }
        reasons += secondValidation.map { "${second.label}: $it" }

        if (first.fragments.size != second.fragments.size) {
            reasons += "Fragment sequence counts differ"
        }
        val fragmentCount = minOf(first.fragments.size, second.fragments.size)
        var comparedSamples = 0

        for (fragmentIndex in 0 until fragmentCount) {
            val a = first.fragments[fragmentIndex]
            val b = second.fragments[fragmentIndex]
            compareTime(
                "fragment $fragmentIndex base decode time",
                a.baseDecodeTimeTicks, first.track.timescale,
                b.baseDecodeTimeTicks, second.track.timescale, reasons
            )
            if (a.sampleCount != b.sampleCount) {
                reasons += "fragment $fragmentIndex sample counts differ"
            }
            if (a.sampleTimings.size != b.sampleTimings.size) {
                reasons += "fragment $fragmentIndex sample timing table sizes differ"
            }
            val sampleCount = minOf(a.sampleTimings.size, b.sampleTimings.size)
            for (sampleIndex in 0 until sampleCount) {
                val left = a.sampleTimings[sampleIndex]
                val right = b.sampleTimings[sampleIndex]
                val label = "fragment $fragmentIndex sample $sampleIndex"
                compareTime("$label decode time", left.decodeTimeTicks, first.track.timescale,
                    right.decodeTimeTicks, second.track.timescale, reasons)
                compareTime("$label presentation time", left.presentationTimeTicks, first.track.timescale,
                    right.presentationTimeTicks, second.track.timescale, reasons)
                compareTime("$label duration", left.durationTicks, first.track.timescale,
                    right.durationTicks, second.track.timescale, reasons)
                compareTime("$label composition offset", left.compositionOffsetTicks, first.track.timescale,
                    right.compositionOffsetTicks, second.track.timescale, reasons)

                val firstFlags = left.sampleFlags
                val secondFlags = right.sampleFlags
                if (firstFlags == null || secondFlags == null) {
                    reasons += "$label random-access/dependency flags are unavailable"
                } else if (((firstFlags xor secondFlags) and RANDOM_ACCESS_DEPENDENCY_MASK) != 0L) {
                    reasons += "$label random-access/dependency flags differ"
                }
                comparedSamples++
            }
        }

        val pictureResult = HevcPictureIdentityCompatibility.compare(first.pictures, second.pictures)
        reasons += pictureResult.reasons

        return Result(
            compatible = reasons.isEmpty(),
            reasons = reasons.distinct(),
            firstSampleCount = firstValidationSampleCount(first),
            secondSampleCount = firstValidationSampleCount(second),
            comparedFragmentCount = fragmentCount,
            comparedSampleCount = comparedSamples
        )
    }

    private fun validateSequence(sequence: FeedSequence): List<String> {
        val reasons = mutableListOf<String>()
        val track = sequence.track
        val scale = track.timescale
        if (track.handler != "vide") reasons += "track is not a video track"
        if (track.trackId == null) reasons += "track ID is missing"
        if (scale == null || scale <= 0L) reasons += "track timescale is missing or invalid"
        if (sequence.fragments.isEmpty()) reasons += "fragment sequence is empty"
        if (sequence.pictures.isEmpty()) reasons += "parsed picture identity sequence is empty"

        var expectedDecodeTime: Long? = null
        var totalSamples = 0L
        for ((fragmentIndex, fragment) in sequence.fragments.withIndex()) {
            if (track.trackId == null || fragment.trackId != track.trackId) {
                reasons += "fragment $fragmentIndex track ID does not match its init track"
            }
            if (scale == null || scale <= 0L || fragment.timescale != scale) {
                reasons += "fragment $fragmentIndex timescale does not match its init track"
            }
            if (fragment.durationIsEmpty) {
                reasons += "fragment $fragmentIndex is duration-is-empty"
            }
            if (!fragment.timingComplete) reasons += "fragment $fragmentIndex timing is incomplete"
            if (fragment.sampleCount <= 0) reasons += "fragment $fragmentIndex has no media samples"
            if (fragment.sampleTimings.size != fragment.sampleCount) {
                reasons += "fragment $fragmentIndex sample timing table does not match its declared count"
            }
            val base = fragment.baseDecodeTimeTicks
            if (base == null) {
                reasons += "fragment $fragmentIndex base decode time is unavailable"
            } else if (expectedDecodeTime != null && base != expectedDecodeTime) {
                val relation = if (base > expectedDecodeTime) "gap" else "overlap"
                reasons += "fragment $fragmentIndex decode timeline has a $relation: expected $expectedDecodeTime ticks, found $base"
            }

            var cursor = base
            var durationSum = 0L
            for ((sampleIndex, sample) in fragment.sampleTimings.withIndex()) {
                val decodeTime = sample.decodeTimeTicks
                val duration = sample.durationTicks
                val presentationTime = sample.presentationTimeTicks
                val compositionOffset = sample.compositionOffsetTicks

                if (decodeTime == null) {
                    reasons += "fragment $fragmentIndex sample $sampleIndex decode time is unavailable"
                } else if (cursor != null && decodeTime != cursor) {
                    reasons += "fragment $fragmentIndex sample $sampleIndex decode time is not contiguous"
                }
                if (duration == null || duration <= 0L) {
                    reasons += "fragment $fragmentIndex sample $sampleIndex duration is missing or non-positive"
                    cursor = null
                } else {
                    try {
                        durationSum = Math.addExact(durationSum, duration)
                        cursor = decodeTime?.let { Math.addExact(it, duration) }
                    } catch (_: ArithmeticException) {
                        reasons += "fragment $fragmentIndex sample $sampleIndex decode timeline overflows"
                        cursor = null
                    }
                }
                if (decodeTime != null && compositionOffset != null && presentationTime != null) {
                    val expectedPresentation = try {
                        Math.addExact(decodeTime, compositionOffset)
                    } catch (_: ArithmeticException) {
                        null
                    }
                    if (expectedPresentation == null || expectedPresentation != presentationTime) {
                        reasons += "fragment $fragmentIndex sample $sampleIndex presentation time does not equal decode time plus composition offset"
                    }
                } else {
                    reasons += "fragment $fragmentIndex sample $sampleIndex presentation/composition timing is incomplete"
                }
                if (sample.sampleFlags == null) {
                    reasons += "fragment $fragmentIndex sample $sampleIndex random-access/dependency flags are unavailable"
                }
                totalSamples++
            }

            if (fragment.totalDurationTicks == null || fragment.totalDurationTicks != durationSum) {
                reasons += "fragment $fragmentIndex total duration does not match its sample durations"
            }
            expectedDecodeTime = cursor
        }

        if (totalSamples > Int.MAX_VALUE.toLong()) {
            reasons += "fragment sequence sample count exceeds supported bounds"
        }
        if (sequence.pictures.size.toLong() != totalSamples) {
            reasons += "parsed HEVC picture count does not match the fragment sample count"
        }
        sequence.pictures.forEachIndexed { index, picture ->
            if (picture.temporalIdPlus1 !in 1..7) {
                reasons += "sample $index temporal_id_plus1 is outside the HEVC range"
            }
        }
        return reasons.distinct()
    }

    private fun firstValidationSampleCount(sequence: FeedSequence): Int =
        sequence.fragments.sumOf { it.sampleCount.toLong() }
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun compareTime(
        label: String,
        firstTicks: Long?,
        firstScale: Long?,
        secondTicks: Long?,
        secondScale: Long?,
        reasons: MutableList<String>
    ) {
        if (firstTicks == null || secondTicks == null ||
            firstScale == null || secondScale == null ||
            firstScale <= 0L || secondScale <= 0L) {
            reasons += "$label is unavailable"
            return
        }
        val left = BigInteger.valueOf(firstTicks).multiply(BigInteger.valueOf(secondScale))
        val right = BigInteger.valueOf(secondTicks).multiply(BigInteger.valueOf(firstScale))
        if (left != right) reasons += "$label differs"
    }

    private const val RANDOM_ACCESS_DEPENDENCY_MASK = 0x0ff10000L
}
