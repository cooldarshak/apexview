package app.apexview.tiled

import java.security.MessageDigest

/**
 * Resolves genuine HEV1 (as opposed to HVC1) parameter sets carried in a
 * protected CMAF sample. The original CMAF bytes and CBCS protection stay intact.
 *
 * No encrypted bytes are examined as codec syntax: the validated CENC subsample
 * layout must prove every VPS/SPS/PPS NAL completely clear before it is parsed.
 * An incomplete or contradictory triplet is rejected, never guessed.
 */
internal object Hev1InbandParameterSetRecovery {
    fun resolve(
        label: String,
        initialization: ByteArray,
        mediaFragment: ByteArray,
        report: CmafBoxInspector.Report,
    ): CmafBoxInspector.Report {
        val video = report.tracks.singleOrNull { it.handler == "vide" }
            ?: throw PermanentCaptureException("$label: expected exactly one HEVC video track")
        val base = video.hevcConfiguration
            ?: throw PermanentCaptureException("$label: HEV1 init contains no hvcC NAL-length configuration")
        if (base.parameterSetErrors.isEmpty() && base.vps.size == 1 &&
            base.sps.size == 1 && base.pps.size == 1) return report
        require(video.encrypted && video.sampleEntry == "encv" &&
            video.originalFormat == "hev1" && video.protectionScheme == "cbcs") {
            "$label: missing hvcC parameter sets cannot be recovered from an unsupported media/protection type"
        }
        require(base.nalLengthSize in 1..4) { "$label: invalid HEV1 NAL length size" }
        val fragment = CmafBoxInspector.inspect(mediaFragment, report)
        require(fragment.errors.isEmpty()) { "$label: media fragment metadata is invalid" }
        val track = fragment.fragments.singleOrNull { it.trackId == video.trackId }
            ?: throw PermanentCaptureException("$label: no matching video fragment for HEV1 parameter recovery")
        require(track.sampleCount in 1..500) {
            "$label: HEV1 parameter-set recovery has an invalid sample count"
        }

        val captured = linkedMapOf<Int, ByteArray>()
        fun accept(type: Int, bytes: ByteArray) {
            require(bytes.size in 2..MAX_PARAMETER_NAL_BYTES) {
                "$label: in-band HEVC parameter NAL size is unsupported"
            }
            val previous = captured[type]
            require(previous == null || previous.contentEquals(bytes)) {
                "$label: HEV1 uses changing in-band parameter sets within one segment"
            }
            if (previous == null) captured[type] = bytes.copyOf()
        }
        // Never silently replace existing hvcC parameters. A present base NAL must
        // match any in-band parameter of that type bit-for-bit.
        base.rawVpsNals.forEach { accept(32, it) }
        base.rawSpsNals.forEach { accept(33, it) }
        base.rawPpsNals.forEach { accept(34, it) }
        require(base.rawVpsNals.size <= 1 && base.rawSpsNals.size <= 1 &&
            base.rawPpsNals.size <= 1) {
            "$label: ambiguous hvcC parameter-set multiplicity"
        }

        val sampleCount = minOf(track.sampleCount, MAX_DISCOVERY_SAMPLES)
        ProtectedHevcCmafSampleExtractor.forEachSample(
            initializationSegment = initialization,
            mediaFragment = mediaFragment,
            sampleIndices = (0 until sampleCount).toSet(),
            context = "$label: HEV1 in-band parameter recovery",
            discoverHev1ParameterSets = true,
        ) { info, sample ->
            val encryption = info.fragment.sampleEncryptionMetadata.getOrNull(sample.sampleIndex)
                ?: throw PermanentCaptureException("$label: HEV1 sample has no verified CBCS metadata")
            val ranges = encryption.subsampleRanges
                ?: throw PermanentCaptureException("$label: HEV1 sample has no CBCS subsample map")
            val layout = ProtectedHevcSampleLayout.inspect(
                sample.sampleBytes, base.nalLengthSize, ranges, "$label: HEV1 discovery sample"
            )
            layout.nals.filter { it.nalType in 32..34 }.forEach { nal ->
                require(nal.clearNalPrefixBytes == nal.nalLength) {
                    "$label: HEV1 in-band VPS/SPS/PPS is encrypted and cannot be inspected"
                }
                val clearNal = sample.sampleBytes.copyOfRange(nal.nalOffset, nal.nalOffset + nal.nalLength)
                try { accept(nal.nalType, clearNal) } finally { clearNal.fill(0) }
            }
        }
        val vpsNal = captured[32]
        val spsNal = captured[33]
        val ppsNal = captured[34]
        if (vpsNal == null || spsNal == null || ppsNal == null) {
            throw PermanentCaptureException(
                "$label: HEV1 init has incomplete VPS/SPS/PPS and the first $sampleCount " +
                    "real media samples contain no complete clear triplet (VPS=${vpsNal != null}, " +
                    "SPS=${spsNal != null}, PPS=${ppsNal != null})"
            )
        }
        val vps = HevcParameterSets.parseVps(vpsNal)
        val sps = HevcParameterSets.parseSps(spsNal)
        val pps = HevcParameterSets.parsePps(ppsNal, sps)
        require(sps.vpsId == vps.vpsId && pps.spsId == sps.spsId) {
            "$label: recovered HEV1 parameter IDs are inconsistent"
        }
        fun fingerprint(type: String, bytes: ByteArray): String = buildString {
            append(type).append(':')
            MessageDigest.getInstance("SHA-256").digest(bytes).forEach {
                append("%02x".format(it.toInt() and 0xff))
            }
        }
        val resolved = base.copy(
            vpsCount = 1, spsCount = 1, ppsCount = 1,
            vps = listOf(vps), sps = listOf(sps), pps = listOf(pps),
            parameterSetErrors = emptyList(),
            parameterSetFingerprints = listOf(
                fingerprint("vps", vpsNal), fingerprint("sps", spsNal), fingerprint("pps", ppsNal)
            ).sorted(),
            rawVpsNals = listOf(vpsNal.copyOf()),
            rawSpsNals = listOf(spsNal.copyOf()),
            rawPpsNals = listOf(ppsNal.copyOf()),
        )
        android.util.Log.i("ApexviewSingleDecoder",
            "REAL_HEV1_INBAND_PARAMETER_RECOVERY feed=$label parsed=VPS_SPS_PPS " +
                "coded=${sps.width}x${sps.height} displayed=${sps.displayWidth}x${sps.displayHeight} " +
                "sampleCount=$sampleCount protection=UNCHANGED decoderCount=ONE")
        return report.copy(tracks = report.tracks.map { trackMetadata ->
            if (trackMetadata === video) trackMetadata.copy(hevcConfiguration = resolved)
            else trackMetadata
        })
    }

    private const val MAX_PARAMETER_NAL_BYTES = 64 * 1024
    private const val MAX_DISCOVERY_SAMPLES = 96
}
