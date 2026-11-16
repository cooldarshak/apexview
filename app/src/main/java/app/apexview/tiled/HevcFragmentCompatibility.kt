package app.apexview.tiled

import java.math.BigInteger

/**
 * Compares the timing and protection metadata of two candidate fragments.
 *
 * This validator does not compose protected media. It accepts protected metadata only when
 * track, sample-group, senc-override and per-sample KID/IV identities match through process-local
 * keyed fingerprints, and each source's subsample map independently covers its sample.
 * Raw key and IV values are never exposed.
 */
internal object HevcFragmentCompatibility {
    private const val RESERVED_SAMPLE_FLAGS_MASK = 0xf0000000L
    data class Result(val compatible: Boolean, val reasons: List<String>)

    fun compare(
        firstTrack: CmafBoxInspector.TrackMetadata,
        firstFragment: CmafBoxInspector.FragmentMetadata,
        secondTrack: CmafBoxInspector.TrackMetadata,
        secondFragment: CmafBoxInspector.FragmentMetadata
    ): Result {
        val reasons = mutableListOf<String>()

        if (firstTrack.handler != "vide" || secondTrack.handler != "vide") {
            reasons += "Both tracks must be video"
        }
        if (firstTrack.trackId == null || firstTrack.trackId != firstFragment.trackId ||
            secondTrack.trackId == null || secondTrack.trackId != secondFragment.trackId) {
            reasons += "Fragment track ID is missing or does not match its init-segment track"
        }
        if (firstTrack.sampleEntry != secondTrack.sampleEntry ||
            firstTrack.originalFormat != secondTrack.originalFormat) {
            reasons += "Track sample-entry formats differ"
        }
        if (firstFragment.sampleDescriptionIndex == null || secondFragment.sampleDescriptionIndex == null) {
            reasons += "Effective fragment sample-description index is missing"
        } else {
            if (firstFragment.sampleDescriptionIndex != secondFragment.sampleDescriptionIndex) {
                reasons += "Fragment sample-description indices differ"
            }
            if (firstFragment.sampleDescriptionIndex != 1L || secondFragment.sampleDescriptionIndex != 1L) {
                reasons += "Alternate sample-description metadata is not parsed for stitching"
            }
            if (firstFragment.sampleDescriptionIndex > firstTrack.sampleDescriptionCount ||
                secondFragment.sampleDescriptionIndex > secondTrack.sampleDescriptionCount) {
                reasons += "Fragment selects a sample-description index outside stsd"
            }
        }
        if (firstTrack.sampleDescriptionCount <= 0 || secondTrack.sampleDescriptionCount <= 0) {
            reasons += "Track sample-description count is missing"
        }
        if (firstTrack.width != secondTrack.width || firstTrack.height != secondTrack.height) {
            reasons += "Track display dimensions differ"
        }
        val firstScale = firstTrack.timescale
        val secondScale = secondTrack.timescale
        if (firstScale == null || secondScale == null || firstScale <= 0 || secondScale <= 0) {
            reasons += "Init-segment track timescale is missing or invalid"
        }
        if (firstFragment.timescale != null && firstScale != null &&
            firstFragment.timescale != firstScale) {
            reasons += "First fragment timescale does not match its init-segment track"
        }
        if (secondFragment.timescale != null && secondScale != null &&
            secondFragment.timescale != secondScale) {
            reasons += "Second fragment timescale does not match its init-segment track"
        }

        if (firstFragment.durationIsEmpty || secondFragment.durationIsEmpty) {
            reasons += "Empty-duration track fragments cannot be used as media samples"
        }
        if (firstFragment.sampleCount <= 0 || secondFragment.sampleCount <= 0) {
            reasons += "Both fragments must contain at least one media sample"
        }
        if (!firstFragment.timingComplete || !secondFragment.timingComplete) {
            reasons += "Fragment timing is incomplete"
        }
        if (firstFragment.sampleCount != secondFragment.sampleCount) {
            reasons += "Fragment sample counts differ"
        }
        if (firstFragment.sampleTimings.size != firstFragment.sampleCount ||
            secondFragment.sampleTimings.size != secondFragment.sampleCount) {
            reasons += "Sample timing table does not match its declared count"
        }

        if (firstScale != null && secondScale != null && firstScale > 0 && secondScale > 0) {
            compareTime(
                "base decode time",
                firstFragment.baseDecodeTimeTicks,
                secondFragment.baseDecodeTimeTicks,
                firstScale,
                secondScale,
                reasons
            )
            if (firstFragment.sampleTimings.size == secondFragment.sampleTimings.size) {
                firstFragment.sampleTimings.zip(secondFragment.sampleTimings).forEachIndexed { index, pair ->
                    compareTime("sample $index decode time", pair.first.decodeTimeTicks,
                        pair.second.decodeTimeTicks, firstScale, secondScale, reasons)
                    compareTime("sample $index presentation time", pair.first.presentationTimeTicks,
                        pair.second.presentationTimeTicks, firstScale, secondScale, reasons)
                    compareTime("sample $index duration", pair.first.durationTicks,
                        pair.second.durationTicks, firstScale, secondScale, reasons)
                    compareTime("sample $index composition offset", pair.first.compositionOffsetTicks,
                        pair.second.compositionOffsetTicks, firstScale, secondScale, reasons)

                    val firstFlags = pair.first.sampleFlags
                    val secondFlags = pair.second.sampleFlags
                    if (firstFlags == null || secondFlags == null) {
                        reasons += "sample $index sync/random-access flags are unavailable"
                    } else {
                        if ((firstFlags and RESERVED_SAMPLE_FLAGS_MASK) != 0L ||
                            (secondFlags and RESERVED_SAMPLE_FLAGS_MASK) != 0L) {
                            reasons += "sample $index reserved sample_flags bits are non-zero"
                        }
                        val randomAccessMask = 0x0fff0000L
                        if (((firstFlags xor secondFlags) and randomAccessMask) != 0L) {
                            reasons += "sample $index sync/random-access dependency flags differ"
                        }
                    }
                }
            }
        }

        val firstConfig = firstTrack.hevcConfiguration
        val secondConfig = secondTrack.hevcConfiguration
        if (firstConfig == null || secondConfig == null) {
            reasons += "HEVC decoder configuration is missing"
        } else {
            if (firstConfig.parameterSetErrors.isNotEmpty() || secondConfig.parameterSetErrors.isNotEmpty()) {
                reasons += "HEVC parameter-set parsing reported errors"
            }
            if (firstConfig.parameterSetFingerprints.isEmpty() ||
                secondConfig.parameterSetFingerprints.isEmpty()) {
                reasons += "Raw VPS/SPS/PPS NAL fingerprints are missing"
            }
            // Independent source encoders are expected to produce different parameter-set bytes.
            // Parsed source syntax is checked below; WP2 generates fresh output parameter sets.
            val sourceConfigFieldsMatch =
                firstConfig.configurationVersion == secondConfig.configurationVersion &&
                firstConfig.profileSpace == secondConfig.profileSpace &&
                firstConfig.tierFlag == secondConfig.tierFlag &&
                firstConfig.profileIdc == secondConfig.profileIdc &&
                firstConfig.compatibilityFlags == secondConfig.compatibilityFlags &&
                firstConfig.constraintIndicatorFlags == secondConfig.constraintIndicatorFlags &&
                firstConfig.chromaFormat == secondConfig.chromaFormat &&
                firstConfig.bitDepthLuma == secondConfig.bitDepthLuma &&
                firstConfig.bitDepthChroma == secondConfig.bitDepthChroma &&
                firstConfig.temporalLayers == secondConfig.temporalLayers &&
                firstConfig.temporalIdNested == secondConfig.temporalIdNested
            if (!sourceConfigFieldsMatch) {
                reasons += "HEVC source profile, chroma, bit-depth or temporal-layer constraints differ"
            }

            if (firstConfig.vps.size != 1 || firstConfig.sps.size != 1 || firstConfig.pps.size != 1 ||
                secondConfig.vps.size != 1 || secondConfig.sps.size != 1 || secondConfig.pps.size != 1) {
                reasons += "Exactly one parsed VPS, SPS and PPS per track is required"
            } else {
                val parameterResult = HevcParameterSetCompatibility.compare(
                    firstConfig.vps.single(), firstConfig.sps.single(), firstConfig.pps.single(),
                    secondConfig.vps.single(), secondConfig.sps.single(), secondConfig.pps.single()
                )
                reasons += parameterResult.reasons
            }
        }

        if (firstTrack.encrypted != secondTrack.encrypted) {
            reasons += "Track protection status differs"
        }
        if (firstTrack.encrypted || secondTrack.encrypted) {
            if (firstTrack.protectionScheme != secondTrack.protectionScheme) {
                reasons += "Track protection schemes differ"
            }
            val firstProtection = firstTrack.trackEncryption
            val secondProtection = secondTrack.trackEncryption
            if (firstProtection == null || secondProtection == null) {
                reasons += "Track encryption defaults are unavailable"
            } else {
                val firstShape = firstProtection.copy(defaultKidIdentity = null, constantIvIdentity = null)
                val secondShape = secondProtection.copy(defaultKidIdentity = null, constantIvIdentity = null)
                if (firstShape != secondShape) reasons += "Track encryption defaults or protection pattern differ"
                if (firstProtection.defaultIsProtected || secondProtection.defaultIsProtected) {
                    compareProtectedIdentity("Default KID",
                        firstProtection.defaultKidIdentity, secondProtection.defaultKidIdentity, reasons)
                }
                val needsConstantIv =
                    (firstProtection.defaultIsProtected && firstProtection.perSampleIvSize == 0) ||
                    (secondProtection.defaultIsProtected && secondProtection.perSampleIvSize == 0)
                if (needsConstantIv) {
                    compareProtectedIdentity("Track constant IV",
                        firstProtection.constantIvIdentity, secondProtection.constantIvIdentity, reasons)
                }
            }
        }

        if (firstTrack.seigSampleGroupDescriptionStructureComplete !=
            secondTrack.seigSampleGroupDescriptionStructureComplete) {
            reasons += "Track-level seig protection-description completeness differs"
        }
        if (!firstTrack.seigSampleGroupDescriptionStructureComplete ||
            !secondTrack.seigSampleGroupDescriptionStructureComplete) {
            reasons += "Track-level seig protection descriptions are incomplete"
        }
        if (firstTrack.seigSampleGroupDescription != secondTrack.seigSampleGroupDescription) {
            reasons += "Track-level seig protection metadata or identity differs"
        }
        firstTrack.seigSampleGroupDescription?.let {
            validateSeigIdentities(it.entries, "Track-level seig", reasons)
        }
        secondTrack.seigSampleGroupDescription?.let {
            validateSeigIdentities(it.entries, "Track-level seig", reasons)
        }

        val firstCrypto = firstFragment.sampleEncryptionBoxPresent ||
            firstFragment.sampleAuxiliarySizesPresent || firstFragment.sampleAuxiliaryOffsetsPresent ||
            firstFragment.sampleGroupEncryptionPresent
        val secondCrypto = secondFragment.sampleEncryptionBoxPresent ||
            secondFragment.sampleAuxiliarySizesPresent || secondFragment.sampleAuxiliaryOffsetsPresent ||
            secondFragment.sampleGroupEncryptionPresent
        if (firstCrypto != secondCrypto) reasons += "Fragment encryption metadata presence differs"
        if (firstFragment.sampleEncryptionBoxPresent || secondFragment.sampleEncryptionBoxPresent) {
            if (firstFragment.sampleEncryptionCount != firstFragment.sampleCount.toLong() ||
                secondFragment.sampleEncryptionCount != secondFragment.sampleCount.toLong()) {
                reasons += "senc sample count does not match fragment sample count"
            }
            if (firstFragment.sencOverrideIvSize != secondFragment.sencOverrideIvSize) {
                reasons += "senc override IV sizes differ"
            }
            if (firstFragment.sencOverrideIvSize != null || secondFragment.sencOverrideIvSize != null) {
                compareProtectedIdentity("senc override KID",
                    firstFragment.sencOverrideKidIdentity, secondFragment.sencOverrideKidIdentity, reasons)
            }
            if (firstFragment.subsampleEncryptionSignalled != secondFragment.subsampleEncryptionSignalled) {
                reasons += "Subsample encryption signalling differs"
            }
            val firstStructureComplete = firstFragment.sampleEncryptionStructureComplete &&
                firstFragment.sampleEncryptionMetadata.size == firstFragment.sampleCount
            val secondStructureComplete = secondFragment.sampleEncryptionStructureComplete &&
                secondFragment.sampleEncryptionMetadata.size == secondFragment.sampleCount
            if (!firstStructureComplete || !secondStructureComplete) {
                reasons += "Per-sample IV/subsample encryption structure is incomplete"
            } else {
                if (firstFragment.subsampleEncryptionSignalled == true &&
                    !firstFragment.sampleEncryptionRangesCoverSamples) {
                    reasons += "First fragment subsample ranges do not cover the declared sample sizes"
                }
                if (secondFragment.subsampleEncryptionSignalled == true &&
                    !secondFragment.sampleEncryptionRangesCoverSamples) {
                    reasons += "Second fragment subsample ranges do not cover the declared sample sizes"
                }
                if (firstFragment.subsampleEncryptionSignalled == true &&
                    firstFragment.sampleEncryptionMetadata.any { it.subsampleRanges == null }) {
                    reasons += "First fragment subsample map is incomplete"
                }
                if (secondFragment.subsampleEncryptionSignalled == true &&
                    secondFragment.sampleEncryptionMetadata.any { it.subsampleRanges == null }) {
                    reasons += "Second fragment subsample map is incomplete"
                }
                if (firstFragment.subsampleEncryptionSignalled == false &&
                    firstFragment.sampleEncryptionMetadata.any { it.subsampleRanges != null }) {
                    reasons += "First fragment has unexpected subsample entries"
                }
                if (secondFragment.subsampleEncryptionSignalled == false &&
                    secondFragment.sampleEncryptionMetadata.any { it.subsampleRanges != null }) {
                    reasons += "Second fragment has unexpected subsample entries"
                }
            }
        }

        if (firstFragment.sampleEncryptionStructureComplete &&
            secondFragment.sampleEncryptionStructureComplete) {
            compareSampleIvIdentities(firstFragment, secondFragment, reasons)
        }

        if (firstFragment.sampleAuxiliarySizesPresent || secondFragment.sampleAuxiliarySizesPresent ||
            firstFragment.sampleAuxiliaryOffsetsPresent || secondFragment.sampleAuxiliaryOffsetsPresent) {
            if (!firstFragment.sampleAuxiliaryInfoStructureComplete ||
                !secondFragment.sampleAuxiliaryInfoStructureComplete) {
                reasons += "Sample auxiliary size/offset signalling is incomplete"
            }
            if (!firstFragment.sampleAuxiliaryPayloadStructureComplete ||
                !secondFragment.sampleAuxiliaryPayloadStructureComplete) {
                reasons += "Sample auxiliary payload records are incomplete or could not be safely resolved"
            } else {
                if (firstFragment.sampleAuxiliaryPayloadSampleCount != firstFragment.sampleCount ||
                    secondFragment.sampleAuxiliaryPayloadSampleCount != secondFragment.sampleCount) {
                    reasons += "Sample auxiliary payload record count does not match the fragment sample count"
                }
                if (firstFragment.sampleEncryptionMetadata.map { it.ivSize } !=
                    secondFragment.sampleEncryptionMetadata.map { it.ivSize }) {
                    reasons += "Per-sample auxiliary IV-size structure differs"
                }
                // Independently encoded tiles can have different payload sizes. Each source map
                // is checked against its own sample length; cross-feed equality is not required.
                if (!firstFragment.sampleEncryptionRangesCoverSamples ||
                    !secondFragment.sampleEncryptionRangesCoverSamples) {
                    reasons += "Sample auxiliary subsample ranges do not cover the declared sample sizes"
                }
            }
        }
        if (firstFragment.sampleGroupEncryptionPresent != secondFragment.sampleGroupEncryptionPresent) {
            reasons += "seig sample-group encryption override presence differs"
        }
        if (firstFragment.sampleGroupEncryptionPresent || secondFragment.sampleGroupEncryptionPresent) {
            if (!firstFragment.sampleGroupEncryptionStructureComplete ||
                !secondFragment.sampleGroupEncryptionStructureComplete) {
                reasons += "seig sample-group encryption structure is incomplete"
            } else if (firstFragment.sampleGroupEncryptionMetadata != secondFragment.sampleGroupEncryptionMetadata) {
                reasons += "seig sample-group encryption metadata differs"
            }
            firstFragment.sampleGroupEncryptionMetadata?.let {
                validateSeigIdentities(it.entries, "Fragment seig", reasons)
            }
            secondFragment.sampleGroupEncryptionMetadata?.let {
                validateSeigIdentities(it.entries, "Fragment seig", reasons)
            }
        }

        val protectedInput =
            firstTrack.encrypted || secondTrack.encrypted ||
                firstFragment.sampleGroupEncryptionPresent || secondFragment.sampleGroupEncryptionPresent
        if (protectedInput &&
            (firstFragment.subsampleEncryptionSignalled != true ||
                secondFragment.subsampleEncryptionSignalled != true)) {
            reasons += "Protected HEVC slice headers are not exposed through explicit clear/encrypted subsample maps"
        }
        if (requiresPerSampleIv(firstTrack, firstFragment) && !hasSampleEncryptionRecords(firstFragment)) {
            reasons += "First protected fragment is missing per-sample IV records"
        }
        if (requiresPerSampleIv(secondTrack, secondFragment) && !hasSampleEncryptionRecords(secondFragment)) {
            reasons += "Second protected fragment is missing per-sample IV records"
        }
        return Result(reasons.isEmpty(), reasons.distinct())
    }

    private fun compareProtectedIdentity(
        label: String,
        first: ProtectedValueFingerprint?,
        second: ProtectedValueFingerprint?,
        reasons: MutableList<String>
    ) {
        when {
            first == null || second == null -> reasons += "$label identity is unavailable"
            first != second -> reasons += "$label identities differ"
        }
    }

    private fun compareSampleIvIdentities(
        first: CmafBoxInspector.FragmentMetadata,
        second: CmafBoxInspector.FragmentMetadata,
        reasons: MutableList<String>
    ) {
        if (first.sampleEncryptionMetadata.size != second.sampleEncryptionMetadata.size) {
            reasons += "Per-sample encryption record counts differ"
            return
        }
        first.sampleEncryptionMetadata.zip(second.sampleEncryptionMetadata).forEachIndexed { index, pair ->
            if (pair.first.ivSize != pair.second.ivSize) reasons += "Sample $index IV sizes differ"
            if (pair.first.ivSize > 0 || pair.second.ivSize > 0) {
                compareProtectedIdentity("Sample $index IV", pair.first.ivIdentity, pair.second.ivIdentity, reasons)
            }
        }
    }

    private fun validateSeigIdentities(
        entries: List<CmafBoxInspector.SeigProtectionEntry>,
        label: String,
        reasons: MutableList<String>
    ) {
        entries.filter { it.isProtected }.forEachIndexed { index, entry ->
            if (entry.kidIdentity == null) reasons += "$label protected entry $index KID identity is unavailable"
            if (entry.perSampleIvSize == 0 && entry.constantIvIdentity == null) {
                reasons += "$label protected entry $index constant IV identity is unavailable"
            }
        }
    }

    private fun requiresPerSampleIv(
        track: CmafBoxInspector.TrackMetadata,
        fragment: CmafBoxInspector.FragmentMetadata
    ): Boolean =
        (track.trackEncryption?.let { it.defaultIsProtected && it.perSampleIvSize > 0 } == true) ||
            (track.seigSampleGroupDescription?.entries?.any { it.isProtected && it.perSampleIvSize > 0 } == true) ||
            (fragment.sampleGroupEncryptionMetadata?.entries?.any { it.isProtected && it.perSampleIvSize > 0 } == true)

    private fun hasSampleEncryptionRecords(fragment: CmafBoxInspector.FragmentMetadata): Boolean =
        fragment.sampleEncryptionBoxPresent ||
            (fragment.sampleAuxiliarySizesPresent && fragment.sampleAuxiliaryOffsetsPresent &&
                fragment.sampleAuxiliaryPayloadStructureComplete)

    private fun compareTime(
        label: String,
        first: Long?,
        second: Long?,
        firstScale: Long,
        secondScale: Long,
        reasons: MutableList<String>
    ) {
        if (first == null || second == null) {
            reasons += "$label is unavailable"
            return
        }
        val left = BigInteger.valueOf(first).multiply(BigInteger.valueOf(secondScale))
        val right = BigInteger.valueOf(second).multiply(BigInteger.valueOf(firstScale))
        if (left != right) reasons += "$label differs"
    }
}
