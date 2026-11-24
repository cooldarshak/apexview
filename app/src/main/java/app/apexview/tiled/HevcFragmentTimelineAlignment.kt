package app.apexview.tiled

import java.math.BigInteger

/**
 * Read-only analysis of two bounded, ordered CMAF fragment windows.
 *
 * Fragments are paired only by their order within the captured windows. A stable timestamp
 * translation is evidence about the two observed timelines, not proof that the paired samples
 * contain corresponding pictures. This class never changes timestamps or sample data.
 */
internal object HevcFragmentTimelineAlignment {
    enum class Classification {
        STABLE_OFFSET_CANDIDATE,
        VARIABLE_OFFSET,
        NOT_COMPARABLE
    }

    data class Result(
        val classification: Classification,
        /** Second feed minus first feed, rounded to the nearest millisecond. */
        val offsetMillis: Long?,
        val minimumOffsetMillis: Long?,
        val maximumOffsetMillis: Long?,
        val comparedFragments: Int,
        val comparedSamples: Int,
        val reasons: List<String>,
        /** Picture identity is deliberately not inferred from timing metadata. */
        val pictureIdentityCompared: Boolean = false
    )

    fun compare(
        firstTrack: CmafBoxInspector.TrackMetadata,
        firstFragments: List<CmafBoxInspector.FragmentMetadata>,
        secondTrack: CmafBoxInspector.TrackMetadata,
        secondFragments: List<CmafBoxInspector.FragmentMetadata>
    ): Result {
        val reasons = mutableListOf<String>()
        reasons += validateSequence("first", firstTrack, firstFragments)
        reasons += validateSequence("second", secondTrack, secondFragments)

        val firstScale = firstTrack.timescale
        val secondScale = secondTrack.timescale
        if (firstScale == null || firstScale <= 0L || secondScale == null || secondScale <= 0L) {
            reasons += "Both selected tracks must have a valid positive timescale"
        }
        if (firstTrack.handler != "vide" || secondTrack.handler != "vide") {
            reasons += "Both selected tracks must be video tracks"
        }

        if (firstFragments.size != secondFragments.size) {
            reasons += "Ordered fragment-window counts differ"
        }
        if (firstFragments.size < MINIMUM_FRAGMENT_WINDOW ||
            secondFragments.size < MINIMUM_FRAGMENT_WINDOW) {
            reasons += "At least two ordered fragments per feed are required to assess offset stability"
        }

        val pairedFragmentCount = minOf(firstFragments.size, secondFragments.size)
        val offsetNumerators = mutableListOf<BigInteger>()
        var comparedSamples = 0

        if (firstScale != null && firstScale > 0L && secondScale != null && secondScale > 0L) {
            for (fragmentIndex in 0 until pairedFragmentCount) {
                val first = firstFragments[fragmentIndex]
                val second = secondFragments[fragmentIndex]
                if (first.sampleCount != second.sampleCount) {
                    reasons += "Fragment $fragmentIndex sample counts differ"
                }
                if (first.sampleTimings.size != second.sampleTimings.size) {
                    reasons += "Fragment $fragmentIndex sample timing-table sizes differ"
                }

                val sampleCount = minOf(first.sampleTimings.size, second.sampleTimings.size)
                for (sampleIndex in 0 until sampleCount) {
                    val a = first.sampleTimings[sampleIndex]
                    val b = second.sampleTimings[sampleIndex]
                    val label = "Fragment $fragmentIndex sample $sampleIndex"

                    val firstDts = a.decodeTimeTicks
                    val secondDts = b.decodeTimeTicks
                    if (firstDts == null || secondDts == null) {
                        reasons += "$label decode time is unavailable"
                    } else {
                        val delta = BigInteger.valueOf(secondDts)
                            .multiply(BigInteger.valueOf(firstScale))
                            .subtract(
                                BigInteger.valueOf(firstDts)
                                    .multiply(BigInteger.valueOf(secondScale))
                            )
                        offsetNumerators += delta
                    }

                    compareTime(
                        "$label duration",
                        a.durationTicks,
                        firstScale,
                        b.durationTicks,
                        secondScale,
                        reasons
                    )
                    compareTime(
                        "$label composition offset",
                        a.compositionOffsetTicks,
                        firstScale,
                        b.compositionOffsetTicks,
                        secondScale,
                        reasons
                    )

                    val firstPts = a.presentationTimeTicks
                    val secondPts = b.presentationTimeTicks
                    if (firstPts == null || secondPts == null ||
                        firstDts == null || secondDts == null) {
                        reasons += "$label presentation/decode timing is incomplete"
                    } else {
                        val ptsDelta = BigInteger.valueOf(secondPts)
                            .multiply(BigInteger.valueOf(firstScale))
                            .subtract(
                                BigInteger.valueOf(firstPts)
                                    .multiply(BigInteger.valueOf(secondScale))
                            )
                        val dtsDelta = BigInteger.valueOf(secondDts)
                            .multiply(BigInteger.valueOf(firstScale))
                            .subtract(
                                BigInteger.valueOf(firstDts)
                                    .multiply(BigInteger.valueOf(secondScale))
                            )
                        if (ptsDelta != dtsDelta) {
                            reasons += "$label presentation-time offset differs from decode-time offset"
                        }
                    }
                    comparedSamples++
                }
            }
        }

        val denominator = if (firstScale != null && firstScale > 0L &&
            secondScale != null && secondScale > 0L) {
            BigInteger.valueOf(firstScale).multiply(BigInteger.valueOf(secondScale))
        } else {
            null
        }
        val distinctOffsets = offsetNumerators.distinct()
        val variableOffset = distinctOffsets.size > 1
        if (variableOffset) {
            reasons += "Second-minus-first timestamp offset varies across ordered sample indices"
        }

        val status = when {
            variableOffset -> Classification.VARIABLE_OFFSET
            reasons.isEmpty() && pairedFragmentCount >= MINIMUM_FRAGMENT_WINDOW &&
                comparedSamples > 0 -> Classification.STABLE_OFFSET_CANDIDATE
            else -> Classification.NOT_COMPARABLE
        }

        val millis = denominator?.let { scale -> offsetNumerators.firstOrNull()?.let { toMillis(it, scale) } }
        val minimum = denominator?.let { scale ->
            offsetNumerators.minOrNull()?.let { toMillis(it, scale) }
        }
        val maximum = denominator?.let { scale ->
            offsetNumerators.maxOrNull()?.let { toMillis(it, scale) }
        }

        return Result(
            classification = status,
            offsetMillis = millis,
            minimumOffsetMillis = minimum,
            maximumOffsetMillis = maximum,
            comparedFragments = pairedFragmentCount,
            comparedSamples = comparedSamples,
            reasons = reasons.distinct().take(MAX_REPORTED_REASONS)
        )
    }

    private fun validateSequence(
        label: String,
        track: CmafBoxInspector.TrackMetadata,
        fragments: List<CmafBoxInspector.FragmentMetadata>
    ): List<String> {
        val reasons = mutableListOf<String>()
        val scale = track.timescale
        if (track.trackId == null) reasons += "$label track ID is unavailable"
        if (scale == null || scale <= 0L) return reasons + "$label track timescale is unavailable"
        if (fragments.isEmpty()) return reasons + "$label ordered fragment window is empty"

        var expectedNextDecodeTime: Long? = null
        fragments.forEachIndexed { fragmentIndex, fragment ->
            if (track.trackId == null || fragment.trackId != track.trackId) {
                reasons += "$label fragment $fragmentIndex track ID does not match the selected init track"
            }
            if (fragment.timescale != null && fragment.timescale != scale) {
                reasons += "$label fragment $fragmentIndex timescale does not match the selected init track"
            }
            if (fragment.durationIsEmpty) reasons += "$label fragment $fragmentIndex is duration-is-empty"
            if (!fragment.timingComplete) reasons += "$label fragment $fragmentIndex timing is incomplete"
            if (fragment.sampleCount <= 0) reasons += "$label fragment $fragmentIndex has no samples"
            if (fragment.sampleTimings.size != fragment.sampleCount) {
                reasons += "$label fragment $fragmentIndex timing-table size does not match its sample count"
            }

            val base = fragment.baseDecodeTimeTicks
            if (base == null) {
                reasons += "$label fragment $fragmentIndex base decode time is unavailable"
            } else if (expectedNextDecodeTime != null && base != expectedNextDecodeTime) {
                reasons += "$label fragment $fragmentIndex is not contiguous with the preceding fragment"
            }

            var cursor = base
            var durationSum = 0L
            fragment.sampleTimings.forEachIndexed { sampleIndex, sample ->
                val decodeTime = sample.decodeTimeTicks
                val duration = sample.durationTicks
                if (decodeTime == null) {
                    reasons += "$label fragment $fragmentIndex sample $sampleIndex decode time is unavailable"
                    cursor = null
                } else if (cursor != null && decodeTime != cursor) {
                    reasons += "$label fragment $fragmentIndex sample $sampleIndex decode time is not contiguous"
                }

                if (duration == null || duration <= 0L) {
                    reasons += "$label fragment $fragmentIndex sample $sampleIndex duration is missing or non-positive"
                    cursor = null
                } else if (decodeTime != null) {
                    try {
                        durationSum = Math.addExact(durationSum, duration)
                        cursor = Math.addExact(decodeTime, duration)
                    } catch (_: ArithmeticException) {
                        reasons += "$label fragment $fragmentIndex sample $sampleIndex timeline overflows"
                        cursor = null
                    }
                }

                val compositionOffset = sample.compositionOffsetTicks
                val presentationTime = sample.presentationTimeTicks
                if (decodeTime == null || compositionOffset == null || presentationTime == null) {
                    reasons += "$label fragment $fragmentIndex sample $sampleIndex presentation/composition timing is incomplete"
                } else {
                    val expectedPresentation = try {
                        Math.addExact(decodeTime, compositionOffset)
                    } catch (_: ArithmeticException) {
                        null
                    }
                    if (expectedPresentation == null || expectedPresentation != presentationTime) {
                        reasons += "$label fragment $fragmentIndex sample $sampleIndex presentation time is inconsistent"
                    }
                }
            }

            if (fragment.totalDurationTicks == null || fragment.totalDurationTicks != durationSum) {
                reasons += "$label fragment $fragmentIndex total duration does not match sample durations"
            }
            expectedNextDecodeTime = cursor
        }
        return reasons
    }

    private fun compareTime(
        label: String,
        firstTicks: Long?,
        firstScale: Long,
        secondTicks: Long?,
        secondScale: Long,
        reasons: MutableList<String>
    ) {
        if (firstTicks == null || secondTicks == null) {
            reasons += "$label is unavailable"
            return
        }
        val first = BigInteger.valueOf(firstTicks).multiply(BigInteger.valueOf(secondScale))
        val second = BigInteger.valueOf(secondTicks).multiply(BigInteger.valueOf(firstScale))
        if (first != second) reasons += "$label differs"
    }

    private fun toMillis(offsetNumerator: BigInteger, denominator: BigInteger): Long? {
        if (denominator.signum() <= 0) return null
        val scaled = offsetNumerator.multiply(THOUSAND)
        val sign = scaled.signum()
        val absolute = scaled.abs()
        val division = absolute.divideAndRemainder(denominator)
        val rounded = if (division[1].multiply(TWO) >= denominator) {
            division[0].add(BigInteger.ONE)
        } else {
            division[0]
        }
        val signed = if (sign < 0) rounded.negate() else rounded
        return runCatching { signed.longValueExact() }.getOrNull()
    }

    private const val MINIMUM_FRAGMENT_WINDOW = 2
    private const val MAX_REPORTED_REASONS = 8
    private val THOUSAND = BigInteger.valueOf(1_000L)
    private val TWO = BigInteger.valueOf(2L)
}
