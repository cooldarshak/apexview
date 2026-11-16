package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcFragmentSequenceCompatibilityTest {
    @Test
    fun acceptsContinuousSequencesAcrossDifferentExactTimescales() {
        val first = sequence(
            scale = 90_000,
            fragmentStarts = listOf(0, 6_000),
            sampleDuration = 3_000,
            poc = listOf(0, 1, 2, 3)
        )
        val second = sequence(
            scale = 180_000,
            fragmentStarts = listOf(0, 12_000),
            sampleDuration = 6_000,
            poc = listOf(0, 1, 2, 3)
        )

        val result = HevcFragmentSequenceCompatibility.compare(first, second)

        assertTrue(result.reasons.toString(), result.compatible)
        assertEquals(4, result.firstSampleCount)
        assertEquals(4, result.secondSampleCount)
        assertEquals(2, result.comparedFragmentCount)
        assertEquals(4, result.comparedSampleCount)
    }

    @Test
    fun rejectsGapBetweenFragmentsInsteadOfSmoothingTheTimeline() {
        val first = sequence(90_000, listOf(0, 6_001), 3_000, listOf(0, 1, 2, 3))
        val second = sequence(90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3))

        val result = HevcFragmentSequenceCompatibility.compare(first, second)

        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("fragment 1 decode timeline has a gap") })
    }

    @Test
    fun rejectsOverlapBetweenFragments() {
        val first = sequence(90_000, listOf(0, 5_999), 3_000, listOf(0, 1, 2, 3))
        val second = sequence(90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3))

        val result = HevcFragmentSequenceCompatibility.compare(first, second)

        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("fragment 1 decode timeline has an overlap") })
    }

    @Test
    fun rejectsOneTickDecodeOffsetEvenWhenTheFrameCountsMatch() {
        val first = sequence(90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3))
        val second = sequence(90_000, listOf(1, 6_001), 3_000, listOf(0, 1, 2, 3))

        val result = HevcFragmentSequenceCompatibility.compare(first, second)

        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("base decode time differs") })
        assertTrue(result.reasons.any { it.contains("sample 0 decode time differs") })
    }

    @Test
    fun rejectsMissingSampleAndPictureIdentity() {
        val first = sequence(90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3))
        val second = sequence(90_000, listOf(0, 3_000), 3_000, listOf(0, 1, 2))

        val result = HevcFragmentSequenceCompatibility.compare(first, second)

        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("fragment 1 decode timeline has an overlap") })
        assertTrue(result.reasons.any { it.contains("Fragment sequence counts differ") }.not())
        assertTrue(result.reasons.any { it.contains("Per-sample HEVC picture identity counts differ") })
    }

    @Test
    fun rejectsPictureOrderCountAndReferencePictureMismatches() {
        val first = sequence(90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3))
        val pocMismatch = sequence(90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 9, 3))
        val pocResult = HevcFragmentSequenceCompatibility.compare(first, pocMismatch)
        assertFalse(pocResult.compatible)
        assertTrue(pocResult.reasons.any { it.contains("sample 2 full POC differs") })

        val referenceMismatch = sequence(
            90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3),
            referenceOverrides = mapOf(2 to listOf(HevcSliceHeaderParser.ReferencePicture(deltaPoc = -2, pocLsb = null, usedByCurrent = true, longTerm = false)))
        )
        val referenceResult = HevcFragmentSequenceCompatibility.compare(first, referenceMismatch)
        assertFalse(referenceResult.compatible)
        assertTrue(referenceResult.reasons.any { it.contains("sample 2 reference-picture set differs") })
    }

    @Test
    fun rejectsRandomAccessBoundaryMismatch() {
        val first = sequence(90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3))
        val second = sequence(
            90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3),
            randomAccessFlags = mapOf(2 to 0x01010000L)
        )

        val result = HevcFragmentSequenceCompatibility.compare(first, second)

        assertFalse(result.compatible)
        assertFalse(result.reasons.contains("sample 0 random-access/dependency flags differ"))
        assertTrue(result.reasons.any { it.contains("fragment 1 sample 0 random-access/dependency flags differ") })
    }


    @Test
    fun rejectsSamplePaddingFlagMismatch() {
        val first = sequence(90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3))
        val second = sequence(
            90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3),
            randomAccessFlags = mapOf(1 to (0x02000000L or 0x00020000L))
        )

        val result = HevcFragmentSequenceCompatibility.compare(first, second)

        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("sample 1 random-access/dependency flags differ") })
    }

    @Test
    fun rejectsInternallyNonContiguousSampleDecodeTimes() {
        val first = sequence(90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3))
        val badFragment = first.fragments.first().copy(
            sampleTimings = first.fragments.first().sampleTimings.mapIndexed { index, sample ->
                if (index == 1) sample.copy(decodeTimeTicks = 3_001, presentationTimeTicks = 3_001)
                else sample
            }
        )
        val invalid = first.copy(fragments = listOf(badFragment, first.fragments[1]))

        val result = HevcFragmentSequenceCompatibility.compare(invalid, first)

        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("sample 1 decode time is not contiguous") })
    }

    @Test
    fun rejectsCompositionOffsetInconsistentWithPresentationTimestamp() {
        val first = sequence(90_000, listOf(0, 6_000), 3_000, listOf(0, 1, 2, 3))
        val badTiming = first.fragments.first().sampleTimings.first().copy(
            presentationTimeTicks = 250,
            compositionOffsetTicks = 0
        )
        val invalid = first.copy(
            fragments = listOf(
                first.fragments.first().copy(
                    sampleTimings = listOf(badTiming) + first.fragments.first().sampleTimings.drop(1)
                ),
                first.fragments[1]
            )
        )

        val result = HevcFragmentSequenceCompatibility.compare(invalid, first)

        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("presentation time does not equal decode time plus composition offset") })
    }

    private fun sequence(
        scale: Long,
        fragmentStarts: List<Long>,
        sampleDuration: Long,
        poc: List<Long>,
        referenceOverrides: Map<Int, List<HevcSliceHeaderParser.ReferencePicture>> = emptyMap(),
        randomAccessFlags: Map<Int, Long> = emptyMap()
    ): HevcFragmentSequenceCompatibility.FeedSequence {
        val samplesPerFragment = 2
        val track = CmafBoxInspector.TrackMetadata(
            trackId = 1,
            handler = "vide",
            timescale = scale,
            sampleEntry = "hvc1",
            originalFormat = null,
            protectionScheme = null,
            encrypted = false,
            width = 960,
            height = 540,
            hevcConfiguration = null,
            trackEncryption = null,
            sampleEncryptionBoxPresent = false,
            sampleAuxiliarySizesPresent = false,
            sampleAuxiliaryOffsetsPresent = false,
            sampleGroupDescriptionPresent = false,
            sampleToGroupPresent = false
        )
        val fragments = fragmentStarts.mapIndexed { fragmentIndex, start ->
            val timings = List(samplesPerFragment) { sampleIndex ->
                val globalIndex = fragmentIndex * samplesPerFragment + sampleIndex
                val decodeTime = start + sampleIndex * sampleDuration
                CmafBoxInspector.SampleTiming(
                    decodeTimeTicks = decodeTime,
                    presentationTimeTicks = decodeTime,
                    durationTicks = sampleDuration,
                    compositionOffsetTicks = 0,
                    sampleSizeBytes = 100,
                    sampleFlags = randomAccessFlags[globalIndex] ?: 0x02000000L
                )
            }
            CmafBoxInspector.FragmentMetadata(
                trackId = 1,
                timescale = scale,
                baseDecodeTimeTicks = start,
                sampleCount = timings.size,
                totalDurationTicks = sampleDuration * timings.size,
                sampleTimings = timings,
                timingComplete = true,
                sampleEncryptionBoxPresent = false,
                subsampleEncryptionSignalled = null,
                sampleEncryptionCount = null,
                sampleAuxiliarySizesPresent = false,
                sampleAuxiliaryOffsetsPresent = false,
                sampleGroupDescriptionPresent = false,
                sampleToGroupPresent = false,
                sampleDescriptionIndex = 1L
            )
        }
        val pictures = poc.mapIndexed { index, order ->
            HevcPictureIdentityCompatibility.PictureIdentity(
                nalUnitType = if (index == 0) 20 else 1,
                temporalIdPlus1 = 1,
                pictureOrderCount = order,
                sliceType = if (index == 0) 2 else 1,
                noOutputOfPriorPictures = if (index == 0) true else null,
                references = referenceOverrides[index] ?: if (index == 0) emptyList()
                    else listOf(HevcSliceHeaderParser.ReferencePicture(deltaPoc = -1, pocLsb = null, usedByCurrent = true, longTerm = false)),
                picOutputFlag = true,
                shortTermRpsFromSps = false,
                shortTermRpsIndex = null
            )
        }
        return HevcFragmentSequenceCompatibility.FeedSequence(
            label = "synthetic",
            track = track,
            fragments = fragments,
            pictures = pictures
        )
    }
}
