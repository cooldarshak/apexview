package app.apexview.tiled

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the real F1 Build 594 failure at
 * ProtectedHevcCmafSampleExtractor.visitSamples:212.
 *
 * ISO-BMFF sgpd/sbgp can carry non-DRM grouping types. A seig mapping affects
 * the effective CBCS properties and must be checked per sample, not dropped.
 */
class ProtectedCmafSampleGroupCompatibilityTest {
    private val kid = ProtectedValueFingerprint.fromBytes("kid", ByteArray(16) { 0x27 })
    private val otherKid = ProtectedValueFingerprint.fromBytes("kid", ByteArray(16) { 0x37 })

    private fun tenc() = CmafBoxInspector.TrackEncryptionMetadata(
        version = 1, defaultIsProtected = true, perSampleIvSize = 16,
        cryptByteBlock = 1, skipByteBlock = 9, constantIvSize = null,
        defaultKidIdentity = kid
    )

    private fun track(
        defaultSeig: CmafBoxInspector.SampleGroupDescriptionMetadata? = null,
        genericBoxesPresent: Boolean = true,
        seigTrackMapping: Boolean = false
    ) = CmafBoxInspector.TrackMetadata(
        trackId = 1, handler = "vide", timescale = 90_000L,
        sampleEntry = "encv", originalFormat = "hev1", protectionScheme = "cbcs",
        encrypted = true, width = 1920, height = 1080,
        hevcConfiguration = null, trackEncryption = tenc(),
        sampleEncryptionBoxPresent = false, sampleAuxiliarySizesPresent = false,
        sampleAuxiliaryOffsetsPresent = false,
        sampleGroupDescriptionPresent = genericBoxesPresent,
        sampleToGroupPresent = genericBoxesPresent,
        seigSampleGroupDescription = defaultSeig,
        seigSampleToGroupPresent = seigTrackMapping
    )

    private fun entry(
        index: Int = 0x10001,
        key: ProtectedValueFingerprint = kid,
        crypt: Int = 1
    ) = CmafBoxInspector.SeigProtectionEntry(
        entryIndex = index, isProtected = true, perSampleIvSize = 16,
        cryptByteBlock = crypt, skipByteBlock = 9,
        constantIvSize = null, kidIdentity = key
    )

    private fun fragment(
        seig: CmafBoxInspector.SampleGroupEncryptionMetadata? = null,
        seigPresent: Boolean = seig != null,
        groupBoxesPresent: Boolean = true
    ) = CmafBoxInspector.FragmentMetadata(
        trackId = 1, timescale = 90_000L, baseDecodeTimeTicks = 0L,
        sampleCount = 3, totalDurationTicks = 5400L,
        sampleTimings = emptyList(), timingComplete = false,
        sampleEncryptionBoxPresent = false, subsampleEncryptionSignalled = null,
        sampleEncryptionCount = null, sampleAuxiliarySizesPresent = false,
        sampleAuxiliaryOffsetsPresent = false,
        sampleGroupDescriptionPresent = groupBoxesPresent,
        sampleToGroupPresent = groupBoxesPresent,
        sampleGroupEncryptionPresent = seigPresent,
        sampleGroupEncryptionMetadata = seig
    )

    private fun mapped(
        entries: List<CmafBoxInspector.SeigProtectionEntry>,
        runs: List<CmafBoxInspector.SampleGroupRun>
    ) = CmafBoxInspector.SampleGroupEncryptionMetadata(
        descriptionVersion = 2,
        defaultSampleDescriptionIndex = 0,
        entries = entries, runs = runs
    )

    @Test fun unrelatedSampleGroupsDoNotAlterCbcsAndAreAccepted() {
        // The Build 594 predicate rejected this shape only because raw sbgp/sgpd existed.
        val source = fragment(seig = null, seigPresent = false, groupBoxesPresent = true)
        ProtectedCmafSampleGroupCompatibility.requireFragmentEquivalent(
            track(genericBoxesPresent = true), source, "non-seig real-CMAF case"
        )
    }

    @Test fun seigOverridesEquivalentToTencAreSafe() {
        val group = mapped(
            listOf(entry()),
            listOf(CmafBoxInspector.SampleGroupRun(2L, 0x10001L),
                CmafBoxInspector.SampleGroupRun(1L, 0L))
        )
        ProtectedCmafSampleGroupCompatibility.requireFragmentEquivalent(
            track(), fragment(group), "equivalent seig mapping"
        )
    }

    @Test fun seigWithDifferentKidIsRejectedAndNotSilentlyIgnored() {
        val group = mapped(
            listOf(entry(key = otherKid)),
            listOf(CmafBoxInspector.SampleGroupRun(3L, 0x10001L))
        )
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProtectedCmafSampleGroupCompatibility.requireFragmentEquivalent(
                track(), fragment(group), "different-key"
            )
        }
        assertTrue(failure.message!!.contains("KID, IV, encryption pattern"))
    }

    @Test fun seigWithDifferentPatternIsRejected() {
        val group = mapped(
            listOf(entry(crypt = 2)),
            listOf(CmafBoxInspector.SampleGroupRun(3L, 0x10001L))
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedCmafSampleGroupCompatibility.requireFragmentEquivalent(
                track(), fragment(group), "different-cbcs-pattern"
            )
        }
    }

    @Test fun missingOrIncompleteSeigMappingCannotBeTreatedAsDefault() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedCmafSampleGroupCompatibility.requireFragmentEquivalent(
                track(), fragment(seig = null, seigPresent = true), "unresolved-seig"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val incomplete = mapped(listOf(entry()),
                listOf(CmafBoxInspector.SampleGroupRun(2L, 0x10001L)))
            ProtectedCmafSampleGroupCompatibility.requireFragmentEquivalent(
                track(), fragment(incomplete), "unmapped-sample"
            )
        }
    }

    @Test fun outputInitMayRetainNonSeigGroupsButNotDifferentActiveSeigDefaults() {
        ProtectedCmafSampleGroupCompatibility.requireTrackSafe(
            track(defaultSeig = null, genericBoxesPresent = true),
            "non-seig initialization", outputInitialization = true
        )
        val equivalent = CmafBoxInspector.SampleGroupDescriptionMetadata(
            version = 2, defaultSampleDescriptionIndex = 1L,
            entries = listOf(entry(index = 1))
        )
        ProtectedCmafSampleGroupCompatibility.requireTrackSafe(
            track(defaultSeig = equivalent), "equivalent default",
            outputInitialization = true
        )
        val wrong = equivalent.copy(entries = listOf(entry(index = 1, key = otherKid)))
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedCmafSampleGroupCompatibility.requireTrackSafe(
                track(defaultSeig = wrong), "unsafe-output-default",
                outputInitialization = true
            )
        }
    }
}
