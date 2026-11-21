package app.apexview.tiled

/**
 * Checks the *effective* CENC sample-group selection, not whether an ISO-BMFF
 * sample-group box of any grouping type happened to be present. 'roll', 'rap '
 * and other non-'seig' groups carry no encryption override.
 *
 * This composer writes one tenc identity/pattern for the output and cannot
 * represent a second key or a different CBCS pattern for an individual sample.
 * A seig group is therefore supported only when its effective values are
 * identical to tenc. A real override is rejected, never dropped or coerced.
 * Only keyed, process-local value identities are compared.
 */
internal object ProtectedCmafSampleGroupCompatibility {
    private fun matchesTrack(
        entry: CmafBoxInspector.SeigProtectionEntry,
        tenc: CmafBoxInspector.TrackEncryptionMetadata
    ): Boolean =
        entry.isProtected == tenc.defaultIsProtected &&
            entry.perSampleIvSize == tenc.perSampleIvSize &&
            entry.cryptByteBlock == tenc.cryptByteBlock &&
            entry.skipByteBlock == tenc.skipByteBlock &&
            entry.kidIdentity != null &&
            entry.kidIdentity == tenc.defaultKidIdentity &&
            entry.constantIvSize == tenc.constantIvSize &&
            entry.constantIvIdentity == tenc.constantIvIdentity

    fun requireTrackSafe(
        track: CmafBoxInspector.TrackMetadata,
        context: String,
        /** The output fragment omits source seig mappings, so the init default must be safe. */
        outputInitialization: Boolean = false
    ) {
        require(track.seigSampleGroupDescriptionStructureComplete) {
            "$context: active track seig metadata could not be completely parsed"
        }
        require(!track.seigSampleToGroupPresent) {
            "$context: track-level seig sample mapping cannot be safely reproduced"
        }
        if (!outputInitialization) return
        val group = track.seigSampleGroupDescription ?: return
        val defaultIndex = group.defaultSampleDescriptionIndex
        if (defaultIndex == 0L) return
        val tenc = requireNotNull(track.trackEncryption) {
            "$context: protected track has no tenc defaults"
        }
        val entry = group.entries.getOrNull((defaultIndex - 1L).toInt())
        require(entry != null && matchesTrack(entry, tenc)) {
            "$context: track seig default changes CBCS protection and cannot be carried into output"
        }
    }

    fun requireFragmentEquivalent(
        track: CmafBoxInspector.TrackMetadata,
        fragment: CmafBoxInspector.FragmentMetadata,
        context: String
    ) {
        requireTrackSafe(track, context)
        require(fragment.sampleGroupEncryptionStructureComplete) {
            "$context: active seig sample-group mapping is incomplete"
        }
        if (!fragment.sampleGroupEncryptionPresent) return
        val mapped = requireNotNull(fragment.sampleGroupEncryptionMetadata) {
            "$context: active seig sample groups have no validated mapping"
        }
        val tenc = requireNotNull(track.trackEncryption) {
            "$context: active CBCS track encryption defaults are absent"
        }
        require(tenc.defaultIsProtected && tenc.defaultKidIdentity != null) {
            "$context: active CBCS default protection is incomplete"
        }
        val entries = mapped.entries.associateBy { it.entryIndex.toLong() }
        var covered = 0L
        for (run in mapped.runs) {
            require(run.sampleCount > 0 &&
                run.sampleCount <= fragment.sampleCount.toLong() - covered) {
                "$context: seig mapping does not cover the real sample count"
            }
            if (run.groupDescriptionIndex != 0L) {
                val effective = entries[run.groupDescriptionIndex]
                require(effective != null && matchesTrack(effective, tenc)) {
                    "$context: active seig changes the KID, IV, encryption pattern or protected state; " +
                        "the single-CBCS-identity output cannot represent it safely"
                }
            }
            covered = Math.addExact(covered, run.sampleCount)
        }
        require(covered == fragment.sampleCount.toLong()) {
            "$context: seig mapping leaves source samples unaccounted for"
        }
    }
}
