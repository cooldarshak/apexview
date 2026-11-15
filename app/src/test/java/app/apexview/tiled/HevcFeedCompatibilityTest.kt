package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class HevcFeedCompatibilityTest {
    private val matching = HevcFeedCompatibility.Profile("hvc1", 640, 360, 90000, 3600, "cbcs")

    @Test fun acceptsMatchingCompleteProfiles() {
        val result = HevcFeedCompatibility.compare(matching, matching.copy())
        assertTrue(result.compatible)
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun rejectsMissingMetadata() {
        val result = HevcFeedCompatibility.compare(matching, matching.copy(frameDurationTicks = null))
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("Frame duration") })
    }

    @Test fun rejectsDimensionTimingCodecAndEncryptionMismatch() {
        val other = matching.copy(codec = "hev1", width = 1280, timescale = 1000,
            frameDurationTicks = 40, encryptionScheme = "cenc")
        val result = HevcFeedCompatibility.compare(matching, other)
        assertFalse(result.compatible)
        assertTrue(result.reasons.size >= 5)
    }

    @Test fun strictParameterSetGateRejectsUnparsedVuiAndExtensions() {
        val sps = HevcParameterSets.SpsMetadata(
            vpsId=0, spsId=0, maxSubLayers=1, temporalIdNesting=true,
            chromaFormatIdc=1, separateColourPlane=false, width=640, height=360,
            log2MaxPicOrderCntLsb=8, log2CtbSize=6, ctbSize=64,
            picWidthInCtbs=10, picHeightInCtbs=6, sampleAdaptiveOffsetEnabled=false,
            temporalMvpEnabled=false, longTermRefPicsPresent=false, longTermRefPics=emptyList(),
            shortTermRefPicSets=emptyList(),
            profileTierLevel=HevcParameterSets.ProfileTierLevel(0,false,1,0,0,120, emptyList(), emptyList(), emptyList(), emptyList()),
            bitDepthLuma=8, bitDepthChroma=8, conformanceWindowLeft=0, conformanceWindowRight=0,
            conformanceWindowTop=0, conformanceWindowBottom=0, displayWidth=640, displayHeight=360,
            maxDecPicBuffering=listOf(4), maxNumReorderPics=listOf(0), maxLatencyIncrease=listOf(0),
            scalingListEnabled=false, scalingListDataPresent=false, ampEnabled=false, pcmEnabled=false,
            strongIntraSmoothingEnabled=true, vuiParametersPresent=false, spsExtensionPresent=false
        )
        val pps = HevcParameterSets.PpsMetadata(
            ppsId=0, spsId=0, dependentSliceSegmentsEnabled=true, outputFlagPresent=false,
            extraSliceHeaderBits=0, tilesEnabled=true, entropyCodingSyncEnabled=false,
            uniformTileSpacing=true, numTileColumns=2, numTileRows=1,
            explicitColumnWidthsMinus1=emptyList(), explicitRowHeightsMinus1=emptyList(),
            tileColumnWidthsInCtbs=listOf(5,5), tileRowHeightsInCtbs=listOf(6),
            loopFilterAcrossTiles=true, weightedPredictionEnabled=false,
            weightedBipredictionEnabled=false, transquantBypassEnabled=false,
            loopFilterAcrossSlicesEnabled=true, deblockingFilterControlPresent=false,
            deblockingFilterOverrideEnabled=null, deblockingFilterDisabled=null,
            betaOffsetDiv2=null, tcOffsetDiv2=null, scalingListDataPresent=false,
            listsModificationPresent=false, log2ParallelMergeLevelMinus2=0,
            sliceSegmentHeaderExtensionPresent=false, extensionPresent=false, extensionFlags=null
        )
        assertTrue(HevcFeedCompatibility.compareParameterSets(sps, pps, sps.copy(), pps.copy()).compatible)
        val vui = HevcFeedCompatibility.compareParameterSets(sps.copy(vuiParametersPresent=true), pps, sps, pps)
        assertFalse(vui.compatible)
        assertTrue(vui.reasons.any { it.contains("VUI") })
        val ppsExtension = HevcFeedCompatibility.compareParameterSets(sps, pps.copy(extensionPresent=true), sps, pps)
        assertFalse(ppsExtension.compatible)
    }

    @Test fun rejectsUnknownCodec() {
        val result = HevcFeedCompatibility.compare(matching.copy(codec = null), matching)
        assertFalse(result.compatible)
    }
}
