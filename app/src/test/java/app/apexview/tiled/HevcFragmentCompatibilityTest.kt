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

    @Test fun rejectsFragmentTimescaleThatDisagreesWithInitTrack() {
        val first = fragment(45000, 4500, listOf(
            CmafBoxInspector.SampleTiming(4500, 4560, 1500, 60)
        ))
        val second = fragment(90000, 9000, listOf(
            CmafBoxInspector.SampleTiming(9000, 9120, 3000, 120)
        ))
        val result = HevcFragmentCompatibility.compare(
            track(90000), first, track(90000), second
        )

        assertTrue(result.reasons.any { it.contains("First fragment timescale") })
        assertFalse(result.compatible)
    }

    @Test fun rejectsMissingInitTrackIdentityAndDurationEmptyFragments() {
        val timed = fragment(90000, 9000, listOf(
            CmafBoxInspector.SampleTiming(9000, 9120, 3000, 120)
        ))
        val empty = timed.copy(durationIsEmpty = true)
        val result = HevcFragmentCompatibility.compare(
            track(90000).copy(trackId = null), empty, track(90000), timed
        )

        assertTrue(result.reasons.any { it.contains("Fragment track ID is missing") })
        assertTrue(result.reasons.any { it.contains("Empty-duration track fragments") })
        assertFalse(result.compatible)
    }

    @Test fun rejectsAlternateSampleDescriptionIndexesEvenWhenBothTracksExposeTheSameSampleEntry() {
        val first = fragment(90000, 9000, listOf(
            CmafBoxInspector.SampleTiming(9000, 9120, 3000, 120)
        )).copy(sampleDescriptionIndex = 2)
        val second = first.copy()
        val result = HevcFragmentCompatibility.compare(
            track(90000).copy(sampleDescriptionCount = 2), first,
            track(90000).copy(sampleDescriptionCount = 2), second
        )
        assertTrue(result.reasons.any { it.contains("Alternate sample-description metadata") })
        assertFalse(result.compatible)
    }

    @Test fun doesNotRequireIndependentSourceParameterSetBytesToBeIdentical() {
        val firstConfig = config(listOf("vps:aa", "sps:bb", "pps:cc"))
        val secondConfig = config(listOf("vps:11", "sps:22", "pps:33"))
        val firstTrack = track(90000).copy(hevcConfiguration = firstConfig)
        val secondTrack = track(90000).copy(hevcConfiguration = secondConfig)
        val timed = fragment(90000, 0,
            listOf(CmafBoxInspector.SampleTiming(0, 0, 3000, 0)))
        val result = HevcFragmentCompatibility.compare(firstTrack, timed, secondTrack, timed)
        assertFalse(result.reasons.any { it.contains("Raw VPS/SPS/PPS NAL bytes differ") })
        assertTrue(result.reasons.any { it.contains("Exactly one parsed VPS, SPS and PPS") })
        assertFalse(result.compatible)
    }

    @Test fun rejectsMissingRawParameterSetFingerprints() {
        val incompleteConfig = config(emptyList())
        val track = track(90000).copy(hevcConfiguration = incompleteConfig)
        val timed = fragment(90000, 0,
            listOf(CmafBoxInspector.SampleTiming(0, 0, 3000, 0)))
        val result = HevcFragmentCompatibility.compare(track, timed, track.copy(), timed)
        assertTrue(result.reasons.any { it.contains("fingerprints are missing") })
        assertFalse(result.compatible)
    }

    @Test fun rejectsSeigOverrideWithIncompleteStructureAndDoesNotAcceptProtectedSeigWithoutKidProof() {
        val plain = fragment(90000, 0,
            listOf(CmafBoxInspector.SampleTiming(0, 0, 3000, 0)))
        val protectedEntry = CmafBoxInspector.SeigProtectionEntry(
            entryIndex = 1, isProtected = true, perSampleIvSize = 8,
            cryptByteBlock = 1, skipByteBlock = 9, constantIvSize = null
        )
        val groupMetadata = CmafBoxInspector.SampleGroupEncryptionMetadata(
            descriptionVersion = 1,
            defaultSampleDescriptionIndex = 0,
            entries = listOf(protectedEntry),
            runs = listOf(CmafBoxInspector.SampleGroupRun(1, 1))
        )
        val incomplete = plain.copy(
            sampleGroupEncryptionPresent = true,
            sampleGroupEncryptionStructureComplete = false
        )
        val incompleteResult = HevcFragmentCompatibility.compare(
            track(90000), incomplete, track(90000), plain
        )
        assertTrue(incompleteResult.reasons.any { it.contains("presence differs") })
        assertTrue(incompleteResult.reasons.any { it.contains("structure is incomplete") })

        val protected = plain.copy(
            sampleGroupEncryptionPresent = true,
            sampleGroupEncryptionStructureComplete = true,
            sampleGroupEncryptionMetadata = groupMetadata
        )
        val result = HevcFragmentCompatibility.compare(
            track(90000), protected, track(90000), protected.copy()
        )
        assertTrue(result.reasons.any { it.contains("KID identity has not been compared") })
        assertFalse(result.compatible)
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
        assertTrue(result.reasons.any { it.contains("Per-sample IV/subsample encryption structure is incomplete") })
    }

    @Test fun rejectsSampleSyncAndRandomAccessDependencyFlagMismatch() {
        val first = fragment(90000, 0, listOf(
            CmafBoxInspector.SampleTiming(0, 0, 3000, 0, sampleFlags = 0x02000000L)
        ))
        val second = fragment(90000, 0, listOf(
            CmafBoxInspector.SampleTiming(0, 0, 3000, 0, sampleFlags = 0x02010000L)
        ))
        val result = HevcFragmentCompatibility.compare(
            track(90000), first, track(90000), second
        )
        assertTrue(result.reasons.any { it.contains("sync/random-access dependency flags differ") })
    }

    @Test fun rejectsReservedSampleFlagBitsEvenWhenBothFeedsAgree() {
        val malformed = fragment(90000, 0, listOf(
            CmafBoxInspector.SampleTiming(0, 0, 3000, 0, sampleFlags = 0x12000000L)
        ))
        val result = HevcFragmentCompatibility.compare(
            track(90000), malformed, track(90000), malformed.copy()
        )
        assertTrue(result.reasons.any { it.contains("reserved sample_flags bits are non-zero") })
        assertFalse(result.compatible)
    }

    @Test fun rejectsMissingSampleSyncFlagsInsteadOfAssumingRandomAccess() {
        val first = fragment(90000, 0, listOf(
            CmafBoxInspector.SampleTiming(0, 0, 3000, 0)
        )).copy(sampleTimings = listOf(
            CmafBoxInspector.SampleTiming(0, 0, 3000, 0, sampleFlags = null)
        ))
        val second = fragment(90000, 0, listOf(
            CmafBoxInspector.SampleTiming(0, 0, 3000, 0, sampleFlags = 0x02000000L)
        ))
        val result = HevcFragmentCompatibility.compare(
            track(90000), first, track(90000), second
        )
        assertTrue(result.reasons.any { it.contains("sync/random-access flags are unavailable") })
    }

    private fun config(parameterSetFingerprints: List<String>) =
        CmafBoxInspector.HevcConfiguration(
            configurationVersion = 1,
            profileSpace = 0,
            tierFlag = false,
            profileIdc = 1,
            compatibilityFlags = 0,
            constraintIndicatorFlags = 0,
            levelIdc = 120,
            minSpatialSegmentationIdc = 0,
            parallelismType = 0,
            chromaFormat = 1,
            bitDepthLuma = 8,
            bitDepthChroma = 8,
            averageFrameRate = 0,
            constantFrameRate = 0,
            temporalLayers = 1,
            temporalIdNested = true,
            nalLengthSize = 4,
            vpsCount = 1,
            spsCount = 1,
            ppsCount = 1,
            vps = emptyList(),
            sps = emptyList(),
            pps = emptyList(),
            parameterSetErrors = emptyList(),
            parameterSetFingerprints = parameterSetFingerprints
        )

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
        sampleTimings = samples.map { sample ->
            if (sample.sampleFlags == null) sample.copy(sampleFlags = 0x02000000L) else sample
        },
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
