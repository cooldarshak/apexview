package app.apexview.tiled

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcFragmentCompatibilityTest {
    @Test fun comparesTimestampsAsExactMediaTimesAcrossDifferentTimescales() {
        val first = fragment(90000, 9000, listOf(
            CmafBoxInspector.SampleTiming(9000, 9120, 3000, 120),
            CmafBoxInspector.SampleTiming(12000, 11940, 3000, -60)
        ))
        val second = fragment(45000, 4500, listOf(
            CmafBoxInspector.SampleTiming(4500, 4560, 1500, 60),
            CmafBoxInspector.SampleTiming(6000, 5970, 1500, -30)
        ))
        val result = HevcFragmentCompatibility.compare(track(90000), first, track(45000), second)

        assertTrue(result.reasons.toString(), result.reasons.none {
            it.contains("decode time differs") ||
                it.contains("presentation time differs") ||
                it.contains("duration differs") ||
                it.contains("composition offset differs")
        })
        // No HEVC init configuration is supplied by this timing-focused fixture.
        assertTrue(result.reasons.any { it.contains("HEVC decoder configuration is missing") })
        assertFalse(result.compatible)
    }

    @Test fun rejectsDecodeOrPresentationClockDrift() {
        val first = fragment(90000, 9000, listOf(
            CmafBoxInspector.SampleTiming(9000, 9100, 3000, 100)
        ))
        val second = fragment(90000, 9000, listOf(
            CmafBoxInspector.SampleTiming(9000, 9200, 3000, 200)
        ))
        val result = HevcFragmentCompatibility.compare(track(90000), first, track(90000), second)

        assertTrue(result.reasons.any { it.contains("sample 0 presentation time differs") })
        assertTrue(result.reasons.any { it.contains("sample 0 composition offset differs") })
    }

    @Test fun rejectsIncompleteTimingAndEncryptedFragmentsWithoutCompleteCryptoProof() {
        val incomplete = fragment(
            90000, null,
            listOf(CmafBoxInspector.SampleTiming(null, null, null, 0)),
            complete = false
        )
        val encrypted = fragment(
            90000, 0,
            listOf(CmafBoxInspector.SampleTiming(0, 0, 3000, 0)),
            encrypted = true
        ).copy(
            sampleEncryptionCount = 1,
            subsampleEncryptionSignalled = true
        )
        val protectedTrack = track(90000, encrypted = true)
        val result = HevcFragmentCompatibility.compare(
            track(90000), incomplete, protectedTrack, encrypted
        )

        assertTrue(result.reasons.any { it.contains("Fragment timing is incomplete") })
        assertTrue(result.reasons.any { it.contains("Default KID identity has not been compared") })
        assertTrue(result.reasons.any { it.contains("Per-sample IV and subsample encryption maps") })
    }

    private fun track(timescale: Long, encrypted: Boolean = false) =
        CmafBoxInspector.TrackMetadata(
            trackId = 1,
            handler = "vide",
            timescale = timescale,
            sampleEntry = if (encrypted) "encv" else "hvc1",
            originalFormat = if (encrypted) "hvc1" else null,
            protectionScheme = if (encrypted) "cbcs" else null,
            encrypted = encrypted,
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
        timescale: Long,
        baseTime: Long?,
        samples: List<CmafBoxInspector.SampleTiming>,
        complete: Boolean = true,
        encrypted: Boolean = false
    ) = CmafBoxInspector.FragmentMetadata(
        trackId = 1,
        timescale = timescale,
        baseDecodeTimeTicks = baseTime,
        sampleCount = samples.size,
        totalDurationTicks = samples.mapNotNull { it.durationTicks }.takeIf { it.size == samples.size }
            ?.sum(),
        sampleTimings = samples,
        timingComplete = complete,
        sampleEncryptionBoxPresent = encrypted,
        subsampleEncryptionSignalled = if (encrypted) true else null,
        sampleEncryptionCount = if (encrypted) samples.size.toLong() else null,
        sampleAuxiliarySizesPresent = false,
        sampleAuxiliaryOffsetsPresent = false,
        sampleGroupDescriptionPresent = false,
        sampleToGroupPresent = false
    )
}
