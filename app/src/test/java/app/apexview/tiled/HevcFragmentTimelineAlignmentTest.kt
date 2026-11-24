package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcFragmentTimelineAlignmentTest {

    @Test
    fun reportsStableNonZeroOffsetAcrossTwoContiguousWindowsWithoutClaimingPictureIdentity() {
        val firstTrack = track(trackId = 1L, timescale = 50_000L)
        val secondTrack = track(trackId = 7L, timescale = 50_000L)
        val first = listOf(fragment(1L, 50_000L, 0L), fragment(1L, 50_000L, 2_000L))
        val second = listOf(fragment(7L, 50_000L, 576_000L), fragment(7L, 50_000L, 578_000L))

        val result = HevcFragmentTimelineAlignment.compare(firstTrack, first, secondTrack, second)

        assertEquals(HevcFragmentTimelineAlignment.Classification.STABLE_OFFSET_CANDIDATE, result.classification)
        assertEquals(11_520L, result.offsetMillis)
        assertEquals(11_520L, result.minimumOffsetMillis)
        assertEquals(11_520L, result.maximumOffsetMillis)
        assertEquals(2, result.comparedFragments)
        assertEquals(4, result.comparedSamples)
        assertFalse(result.pictureIdentityCompared)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun reportsVariableOffsetWhenTheSecondFeedTimelineDriftsWithinTheWindow() {
        val firstTrack = track(1L, 50_000L)
        val secondTrack = track(7L, 50_000L)
        val first = listOf(fragment(1L, 50_000L, 0L), fragment(1L, 50_000L, 2_000L))
        val second = listOf(
            fragment(7L, 50_000L, 576_000L, listOf(1_000L, 1_020L)),
            fragment(7L, 50_000L, 578_020L)
        )

        val result = HevcFragmentTimelineAlignment.compare(firstTrack, first, secondTrack, second)

        assertEquals(HevcFragmentTimelineAlignment.Classification.VARIABLE_OFFSET, result.classification)
        assertTrue(result.reasons.any { it.contains("offset varies") })
        assertTrue(result.reasons.any { it.contains("duration differs") })
        assertFalse(result.pictureIdentityCompared)
    }

    @Test
    fun comparesDifferentTimescalesUsingExactRationalTime() {
        val firstTrack = track(1L, 50_000L)
        val secondTrack = track(7L, 25_000L)
        val first = listOf(fragment(1L, 50_000L, 0L), fragment(1L, 50_000L, 2_000L))
        val second = listOf(
            fragment(7L, 25_000L, 28_800L, listOf(500L, 500L)),
            fragment(7L, 25_000L, 29_800L, listOf(500L, 500L))
        )

        val result = HevcFragmentTimelineAlignment.compare(firstTrack, first, secondTrack, second)

        assertEquals(HevcFragmentTimelineAlignment.Classification.STABLE_OFFSET_CANDIDATE, result.classification)
        assertEquals(1_152L, result.offsetMillis)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun refusesToCallOneFragmentAStableWindow() {
        val firstTrack = track(1L, 50_000L)
        val secondTrack = track(7L, 50_000L)

        val result = HevcFragmentTimelineAlignment.compare(
            firstTrack,
            listOf(fragment(1L, 50_000L, 0L)),
            secondTrack,
            listOf(fragment(7L, 50_000L, 576_000L))
        )

        assertEquals(HevcFragmentTimelineAlignment.Classification.NOT_COMPARABLE, result.classification)
        assertTrue(result.reasons.any { it.contains("At least two ordered fragments") })
    }

    @Test
    fun rejectsWindowWithDecodeTimelineGap() {
        val firstTrack = track(1L, 50_000L)
        val secondTrack = track(7L, 50_000L)
        val first = listOf(fragment(1L, 50_000L, 0L), fragment(1L, 50_000L, 3_000L))
        val second = listOf(fragment(7L, 50_000L, 576_000L), fragment(7L, 50_000L, 579_000L))

        val result = HevcFragmentTimelineAlignment.compare(firstTrack, first, secondTrack, second)

        assertEquals(HevcFragmentTimelineAlignment.Classification.NOT_COMPARABLE, result.classification)
        assertTrue(result.reasons.any { it.contains("not contiguous") })
    }

    private fun track(trackId: Long, timescale: Long) = CmafBoxInspector.TrackMetadata(
        trackId = trackId,
        handler = "vide",
        timescale = timescale,
        sampleEntry = "hvc1",
        originalFormat = "hvc1",
        protectionScheme = null,
        encrypted = false,
        width = 1920,
        height = 1080,
        hevcConfiguration = null,
        trackEncryption = null,
        sampleEncryptionBoxPresent = false,
        sampleAuxiliarySizesPresent = false,
        sampleAuxiliaryOffsetsPresent = false,
        sampleGroupDescriptionPresent = false,
        sampleToGroupPresent = false
    )

    private fun fragment(
        trackId: Long,
        timescale: Long,
        baseDecodeTime: Long,
        durations: List<Long> = listOf(1_000L, 1_000L)
    ): CmafBoxInspector.FragmentMetadata {
        var decodeTime = baseDecodeTime
        val samples = durations.map { duration ->
            CmafBoxInspector.SampleTiming(
                decodeTimeTicks = decodeTime,
                presentationTimeTicks = decodeTime,
                durationTicks = duration,
                compositionOffsetTicks = 0L,
                sampleSizeBytes = 128L,
                sampleFlags = 0L
            ).also {
                decodeTime = Math.addExact(decodeTime, duration)
            }
        }
        return CmafBoxInspector.FragmentMetadata(
            trackId = trackId,
            timescale = timescale,
            baseDecodeTimeTicks = baseDecodeTime,
            sampleCount = samples.size,
            totalDurationTicks = durations.sum(),
            sampleTimings = samples,
            timingComplete = true,
            sampleEncryptionBoxPresent = false,
            subsampleEncryptionSignalled = null,
            sampleEncryptionCount = null,
            sampleAuxiliarySizesPresent = false,
            sampleAuxiliaryOffsetsPresent = false,
            sampleGroupDescriptionPresent = false,
            sampleToGroupPresent = false
        )
    }
}
