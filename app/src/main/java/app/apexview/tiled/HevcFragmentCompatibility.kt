package app.apexview.tiled

import java.math.BigInteger

/**
 * Compares the timing and protection metadata of two candidate fragments.
 *
 * This is a validator only. It does not merge NAL units or claim that a protected stream can
 * be stitched. Encrypted input is rejected until KID identity and the full per-sample
 * encryption/subsample map are verified without exposing key or IV material.
 */
internal object HevcFragmentCompatibility {
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
            val configFieldsMatch =
                firstConfig.configurationVersion == secondConfig.configurationVersion &&
                firstConfig.profileSpace == secondConfig.profileSpace &&
                firstConfig.tierFlag == secondConfig.tierFlag &&
                firstConfig.profileIdc == secondConfig.profileIdc &&
                firstConfig.compatibilityFlags == secondConfig.compatibilityFlags &&
                firstConfig.constraintIndicatorFlags == secondConfig.constraintIndicatorFlags &&
                firstConfig.levelIdc == secondConfig.levelIdc &&
                firstConfig.minSpatialSegmentationIdc == secondConfig.minSpatialSegmentationIdc &&
                firstConfig.parallelismType == secondConfig.parallelismType &&
                firstConfig.chromaFormat == secondConfig.chromaFormat &&
                firstConfig.bitDepthLuma == secondConfig.bitDepthLuma &&
                firstConfig.bitDepthChroma == secondConfig.bitDepthChroma &&
                firstConfig.averageFrameRate == secondConfig.averageFrameRate &&
                firstConfig.constantFrameRate == secondConfig.constantFrameRate &&
                firstConfig.temporalLayers == secondConfig.temporalLayers &&
                firstConfig.temporalIdNested == secondConfig.temporalIdNested &&
                firstConfig.nalLengthSize == secondConfig.nalLengthSize
            if (!configFieldsMatch) reasons += "HEVC decoder configuration fields differ"

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
            if (firstTrack.trackEncryption == null || secondTrack.trackEncryption == null) {
                reasons += "Track encryption defaults are unavailable"
            } else if (firstTrack.trackEncryption != secondTrack.trackEncryption) {
                reasons += "Track encryption defaults differ"
            }
            reasons += "Default KID identity has not been compared"
        }

        val firstCrypto = firstFragment.sampleEncryptionBoxPresent ||
            firstFragment.sampleAuxiliarySizesPresent || firstFragment.sampleAuxiliaryOffsetsPresent ||
            firstFragment.sampleGroupDescriptionPresent || firstFragment.sampleToGroupPresent
        val secondCrypto = secondFragment.sampleEncryptionBoxPresent ||
            secondFragment.sampleAuxiliarySizesPresent || secondFragment.sampleAuxiliaryOffsetsPresent ||
            secondFragment.sampleGroupDescriptionPresent || secondFragment.sampleToGroupPresent
        if (firstCrypto != secondCrypto) reasons += "Fragment encryption metadata presence differs"
        if (firstFragment.sampleEncryptionBoxPresent || secondFragment.sampleEncryptionBoxPresent) {
            if (firstFragment.sampleEncryptionCount != firstFragment.sampleCount.toLong() ||
                secondFragment.sampleEncryptionCount != secondFragment.sampleCount.toLong()) {
                reasons += "senc sample count does not match fragment sample count"
            }
            if (firstFragment.subsampleEncryptionSignalled != secondFragment.subsampleEncryptionSignalled) {
                reasons += "Subsample encryption signalling differs"
            }
            reasons += "Per-sample IV and subsample encryption maps are not yet validated"
        }
        if (firstFragment.sampleAuxiliarySizesPresent || secondFragment.sampleAuxiliarySizesPresent ||
            firstFragment.sampleAuxiliaryOffsetsPresent || secondFragment.sampleAuxiliaryOffsetsPresent) {
            reasons += "External sample-encryption auxiliary data is not yet parsed"
        }
        if (firstFragment.sampleGroupDescriptionPresent || secondFragment.sampleGroupDescriptionPresent ||
            firstFragment.sampleToGroupPresent || secondFragment.sampleToGroupPresent) {
            reasons += "Sample-group encryption overrides are not yet parsed"
        }

        return Result(reasons.isEmpty(), reasons.distinct())
    }

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
