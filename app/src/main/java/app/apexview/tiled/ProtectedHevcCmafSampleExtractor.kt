package app.apexview.tiled

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts protected HEVC CMAF samples from the actual active init/media byte pair.
 *
 * The parser intentionally supports a strict single-video-track fragment shape:
 * one moof, one traf, one trun with data_offset, one mdat, default-base-is-moof,
 * inline senc with explicit subsample encryption, and CBCS HEVC. Unknown or
 * ambiguous layouts are rejected rather than guessed.
 *
 * No media is decrypted. Raw sample bytes and a selected per-sample IV are
 * returned only to the caller for immediate composition/packaging. The caller
 * must close each ExtractedSample when finished.
 */
internal object ProtectedHevcCmafSampleExtractor {
    class ExtractedSample internal constructor(
        val sampleIndex: Int,
        val sampleBytes: ByteArray,
        /** Null when the active tenc uses a constant IV. Must be zeroed by close(). */
        val perSampleIvBytes: ByteArray?
    ) : AutoCloseable {
        override fun close() {
            sampleBytes.fill(0)
            perSampleIvBytes?.fill(0)
        }
    }

    data class ExtractedFragmentInfo(
        val track: CmafBoxInspector.TrackMetadata,
        val fragment: CmafBoxInspector.FragmentMetadata,
        val timescale: Long,
        val baseDecodeTimeTicks: Long
    )

    data class ExtractedFragment(
        val track: CmafBoxInspector.TrackMetadata,
        val fragment: CmafBoxInspector.FragmentMetadata,
        val timescale: Long,
        val baseDecodeTimeTicks: Long,
        val samples: List<ExtractedSample>
    ) : AutoCloseable {
        override fun close() {
            samples.forEach(ExtractedSample::close)
        }
    }

    private data class Box(
        val type: String,
        val start: Int,
        val size: Int,
        val headerSize: Int
    ) {
        val payloadStart: Int get() = start + headerSize
        val endExclusive: Int get() = start + size
    }

    private data class MediaSampleRange(val offset: Int, val size: Int)

    fun extract(
        initializationSegment: ByteArray,
        mediaFragment: ByteArray,
        sampleIndices: Set<Int>,
        context: String = "Protected F1 CMAF extraction"
    ): ExtractedFragment {
        val retained = ArrayList<ExtractedSample>(sampleIndices.size)
        val info = visitSamples(initializationSegment, mediaFragment, sampleIndices, context) { _, sample ->
            retained += ExtractedSample(
                sample.sampleIndex,
                sample.sampleBytes.copyOf(),
                sample.perSampleIvBytes?.copyOf()
            )
        }
        return ExtractedFragment(
            info.track, info.fragment, info.timescale, info.baseDecodeTimeTicks, retained
        )
    }

    /**
     * Visits selected samples one at a time and zeroes the temporary sample/IV immediately after
     * the callback. The caller must copy only the small amount of data it needs to retain.
     */
    fun forEachSample(
        initializationSegment: ByteArray,
        mediaFragment: ByteArray,
        sampleIndices: Set<Int>,
        context: String = "Protected F1 CMAF extraction",
        onSample: (ExtractedFragmentInfo, ExtractedSample) -> Unit
    ): ExtractedFragmentInfo = visitSamples(
        initializationSegment, mediaFragment, sampleIndices, context, onSample
    )

    private fun visitSamples(
        initializationSegment: ByteArray,
        mediaFragment: ByteArray,
        sampleIndices: Set<Int>,
        context: String,
        onSample: (ExtractedFragmentInfo, ExtractedSample) -> Unit
    ): ExtractedFragmentInfo {
        require(initializationSegment.isNotEmpty()) { "$context: active init segment is empty" }
        require(mediaFragment.isNotEmpty()) { "$context: active media fragment is empty" }
        require(sampleIndices.isNotEmpty()) { "$context: no samples were selected for extraction" }
        require(initializationSegment.size <= MAX_INIT_BYTES) {
            "$context: active init segment exceeds the bounded inspection limit"
        }
        require(mediaFragment.size <= MAX_FRAGMENT_BYTES) {
            "$context: active media fragment exceeds the bounded inspection limit"
        }

        val initReport = CmafBoxInspector.inspect(initializationSegment)
        require(initReport.errors.isEmpty() && initReport.initializationSegmentPresent) {
            "$context: active initialization segment is incomplete: " +
                initReport.errors.joinToString("; ")
        }
        val videoTracks = initReport.tracks.filter { it.handler == "vide" }
        require(videoTracks.size == 1) {
            "$context: initialization segment must identify exactly one video track"
        }
        val track = videoTracks.single()
        require(track.encrypted && track.sampleEntry == "encv" &&
            track.originalFormat in setOf("hvc1", "hev1") &&
            track.protectionScheme == "cbcs") {
            "$context: active video representation is not protected CBCS HEVC"
        }
        val encryption = requireNotNull(track.trackEncryption) {
            "$context: protected video track has no tenc defaults"
        }
        require(encryption.defaultIsProtected && encryption.defaultKidIdentity != null) {
            "$context: active protected track has no verified default KID identity"
        }
        require(encryption.perSampleIvSize in setOf(0, 8, 16)) {
            "$context: unsupported tenc per-sample IV size"
        }
        if (encryption.perSampleIvSize == 0) {
            require(encryption.constantIvSize in setOf(8, 16) &&
                encryption.constantIvIdentity != null) {
                "$context: constant-IV tenc configuration is incomplete"
            }
        }
        require(!track.sampleGroupDescriptionPresent && !track.sampleToGroupPresent &&
            track.seigSampleGroupDescription == null &&
            track.seigSampleGroupDescriptionStructureComplete) {
            "$context: track-level seig sample-group overrides are not supported"
        }
        val hevc = requireNotNull(track.hevcConfiguration) {
            "$context: active video track has no hvcC HEVC configuration"
        }
        require(hevc.parameterSetErrors.isEmpty() && hevc.vps.size == 1 &&
            hevc.sps.size == 1 && hevc.pps.size == 1 &&
            hevc.parameterSetFingerprints.isNotEmpty()) {
            "$context: active hvcC must contain one completely parsed VPS/SPS/PPS triplet"
        }

        val mediaReport = CmafBoxInspector.inspect(mediaFragment, priorInitialization = initReport)
        require(mediaReport.errors.isEmpty()) {
            "$context: active media fragment is incomplete: " +
                mediaReport.errors.joinToString("; ")
        }
        require(mediaReport.fragments.size == 1) {
            "$context: media fragment must contain exactly one track fragment"
        }
        val fragment = mediaReport.fragments.single()
        val timescale = requireNotNull(track.timescale).takeIf { it > 0L }
            ?: error("$context: active video track timescale is invalid")
        val baseDecodeTime = requireNotNull(fragment.baseDecodeTimeTicks) {
            "$context: active media fragment has no tfdt decode-time anchor"
        }
        require(fragment.trackId == track.trackId && fragment.timescale == timescale) {
            "$context: media fragment track/timescale does not match the active initialization"
        }
        require(fragment.timingComplete && fragment.sampleCount > 0 &&
            fragment.sampleTimings.size == fragment.sampleCount) {
            "$context: media fragment sample timing table is incomplete"
        }
        require(fragment.sampleDescriptionIndex == 1L) {
            "$context: media fragment sample-description selection is not the inspected HEVC entry"
        }
        require(!fragment.sampleGroupDescriptionPresent && !fragment.sampleToGroupPresent &&
            !fragment.sampleGroupEncryptionPresent && fragment.sampleGroupEncryptionStructureComplete) {
            "$context: fragment-level sample-group overrides are not supported"
        }
        require(fragment.sencOverrideIvSize == null && fragment.sencOverrideKidIdentity == null) {
            "$context: PIFF/senc override fields are not supported"
        }
        require(fragment.sampleEncryptionBoxPresent &&
            fragment.sampleEncryptionStructureComplete &&
            fragment.sampleEncryptionCount == fragment.sampleCount.toLong() &&
            fragment.sampleEncryptionMetadata.size == fragment.sampleCount &&
            fragment.subsampleEncryptionSignalled == true &&
            fragment.sampleEncryptionRangesCoverSamples) {
            "$context: inline senc subsample-encryption records are required and must cover every sample"
        }
        if (fragment.sampleAuxiliarySizesPresent || fragment.sampleAuxiliaryOffsetsPresent) {
            require(fragment.sampleAuxiliaryInfoStructureComplete &&
                fragment.sampleAuxiliaryPayloadStructureComplete &&
                fragment.sampleAuxiliaryPayloadSampleCount == fragment.sampleCount) {
                "$context: saiz/saio records are incomplete or inconsistent"
            }
            val aux = fragment.sampleAuxiliaryMetadata
            require(aux != null &&
                (aux.auxiliaryInfoType == null || aux.auxiliaryInfoType == "cbcs") &&
                (aux.auxiliaryInfoParameter == null || aux.auxiliaryInfoParameter == 0L)) {
                "$context: unsupported CENC auxiliary-info type or parameter"
            }
        }
        sampleIndices.forEach { index ->
            require(index in 0 until fragment.sampleCount) {
                "$context: requested sample index $index is outside the active fragment"
            }
        }

        val top = parseBoxes(mediaFragment, 0, mediaFragment.size)
        val moofs = top.filter { it.type == "moof" }
        val mdats = top.filter { it.type == "mdat" }
        require(moofs.size == 1 && mdats.size == 1) {
            "$context: exactly one moof and one mdat are required"
        }
        val moof = moofs.single()
        val mdat = mdats.single()
        val moofChildren = children(mediaFragment, moof)
        val trafs = moofChildren.filter { it.type == "traf" }
        require(trafs.size == 1) {
            "$context: exactly one traf is required in the media fragment"
        }
        val trafChildren = children(mediaFragment, trafs.single())
        val tfhds = trafChildren.filter { it.type == "tfhd" }
        val tfdtBoxes = trafChildren.filter { it.type == "tfdt" }
        val truns = trafChildren.filter { it.type == "trun" }
        val sencBoxes = trafChildren.filter { it.type == "senc" }
        require(tfhds.size == 1 && tfdtBoxes.size == 1 && truns.size == 1 && sencBoxes.size == 1) {
            "$context: exactly one tfhd, tfdt, trun and inline senc are required"
        }
        val trackId = parseTfhd(mediaFragment, tfhds.single(), context)
        require(trackId == fragment.trackId && trackId == track.trackId) {
            "$context: tfhd track ID does not match the active video track"
        }
        val rawTfdt = parseTfdt(mediaFragment, tfdtBoxes.single(), context)
        require(rawTfdt == baseDecodeTime) {
            "$context: tfdt differs from the inspected fragment decode-time anchor"
        }
        val ranges = parseTrunRanges(
            mediaFragment, moof, mdat, truns.single(), fragment, context
        )
        val selectedIvs = parseSenc(
            mediaFragment, sencBoxes.single(), encryption.perSampleIvSize,
            fragment, sampleIndices, context
        )

        val info = ExtractedFragmentInfo(track, fragment, timescale, baseDecodeTime)
        try {
            sampleIndices.sorted().forEach { index ->
                val range = ranges[index]
                val sampleBytes = mediaFragment.copyOfRange(range.offset, range.offset + range.size)
                val temporary = ExtractedSample(index, sampleBytes, selectedIvs.remove(index))
                try {
                    onSample(info, temporary)
                } finally {
                    temporary.close()
                }
            }
        } finally {
            selectedIvs.values.forEach { it.fill(0) }
        }
        return info
    }

    private fun parseTfhd(data: ByteArray, box: Box, context: String): Long {
        val p = payload(data, box)
        require(p.remaining() >= 8) { "$context: truncated tfhd" }
        val version = u8(p)
        require(version == 0) { "$context: unsupported tfhd version" }
        val flags = readFlags(p)
        val knownFlags = 0x000001 or 0x000002 or 0x000008 or 0x000010 or
            0x000020 or 0x010000 or 0x020000
        require((flags and knownFlags.inv()) == 0) { "$context: unsupported tfhd flags" }
        require((flags and 0x000001) == 0 && (flags and 0x020000) != 0) {
            "$context: tfhd must use default-base-is-moof without an explicit base_data_offset"
        }
        require((flags and 0x010000) == 0) { "$context: duration-is-empty traf is not supported" }
        val trackId = u32(p)
        if ((flags and 0x000002) != 0) skip(p, 4)
        if ((flags and 0x000008) != 0) skip(p, 4)
        if ((flags and 0x000010) != 0) skip(p, 4)
        if ((flags and 0x000020) != 0) skip(p, 4)
        require(!p.hasRemaining() && trackId > 0L) {
            "$context: malformed or invalid tfhd"
        }
        return trackId
    }

    private fun parseTfdt(data: ByteArray, box: Box, context: String): Long {
        val p = payload(data, box)
        require(p.remaining() >= 8) { "$context: truncated tfdt" }
        val version = u8(p)
        val flags = readFlags(p)
        require(flags == 0) { "$context: unsupported tfdt flags" }
        val value = when (version) {
            0 -> u32(p)
            1 -> {
                require(p.remaining() >= 8) { "$context: truncated version-1 tfdt" }
                p.long.also { require(it >= 0L) { "$context: tfdt exceeds supported signed range" } }
            }
            else -> throw IllegalArgumentException("$context: unsupported tfdt version")
        }
        require(!p.hasRemaining()) { "$context: unexpected trailing tfdt data" }
        return value
    }

    private fun parseTrunRanges(
        data: ByteArray,
        moof: Box,
        mdat: Box,
        box: Box,
        fragment: CmafBoxInspector.FragmentMetadata,
        context: String
    ): List<MediaSampleRange> {
        val p = payload(data, box)
        require(p.remaining() >= 8) { "$context: truncated trun" }
        val version = u8(p)
        require(version in 0..1) { "$context: unsupported trun version" }
        val flags = readFlags(p)
        val knownFlags = 0x000001 or 0x000004 or 0x000100 or 0x000200 or
            0x000400 or 0x000800
        require((flags and knownFlags.inv()) == 0 && (flags and 0x000001) != 0) {
            "$context: trun must have a supported data_offset"
        }
        require(!((flags and 0x000004) != 0 && (flags and 0x000400) != 0)) {
            "$context: trun cannot contain both first_sample_flags and per-sample sample_flags"
        }
        val sampleCount = u32(p)
        require(sampleCount == fragment.sampleCount.toLong()) {
            "$context: trun sample count differs from inspected fragment metadata"
        }
        val dataOffset = p.int.toLong() // signed int32, relative to moof start
        if ((flags and 0x000004) != 0) skip(p, 4)
        repeat(fragment.sampleCount) {
            if ((flags and 0x000100) != 0) skip(p, 4)
            if ((flags and 0x000200) != 0) skip(p, 4)
            if ((flags and 0x000400) != 0) skip(p, 4)
            if ((flags and 0x000800) != 0) skip(p, 4)
        }
        require(!p.hasRemaining()) { "$context: unexpected trailing trun data" }

        var cursor = Math.addExact(moof.start.toLong(), dataOffset)
        require(cursor >= mdat.payloadStart.toLong() && cursor <= mdat.endExclusive.toLong()) {
            "$context: trun data_offset does not point into the mdat payload"
        }
        val result = ArrayList<MediaSampleRange>(fragment.sampleCount)
        fragment.sampleTimings.forEachIndexed { index, timing ->
            val size = requireNotNull(timing.sampleSizeBytes).also {
                require(it > 0L && it <= MAX_SAMPLE_BYTES.toLong()) {
                    "$context: sample $index has unsupported sample_size"
                }
            }
            val end = Math.addExact(cursor, size)
            require(cursor >= mdat.payloadStart.toLong() && end <= mdat.endExclusive.toLong()) {
                "$context: sample $index byte range extends outside mdat"
            }
            require(cursor <= Int.MAX_VALUE.toLong() && end <= Int.MAX_VALUE.toLong()) {
                "$context: sample $index byte range exceeds addressable input size"
            }
            result += MediaSampleRange(cursor.toInt(), size.toInt())
            cursor = end
        }
        require(result.isNotEmpty()) { "$context: trun contains no media samples" }
        return result
    }

    private fun parseSenc(
        data: ByteArray,
        box: Box,
        ivSize: Int,
        fragment: CmafBoxInspector.FragmentMetadata,
        selectedIndices: Set<Int>,
        context: String
    ): MutableMap<Int, ByteArray> {
        val p = payload(data, box)
        require(p.remaining() >= 8) { "$context: truncated senc" }
        val version = u8(p)
        val flags = readFlags(p)
        require(version == 0 && (flags and 0x000001) == 0 &&
            (flags and 0x000002) != 0 && (flags and 0xfffffc) == 0) {
            "$context: senc must use version 0 with explicit subsample encryption and no PIFF override"
        }
        val sampleCount = u32(p)
        require(sampleCount == fragment.sampleCount.toLong()) {
            "$context: senc sample count differs from the media fragment"
        }
        val result = linkedMapOf<Int, ByteArray>()
        try {
            repeat(fragment.sampleCount) { index ->
                val rawIv = if (ivSize > 0) {
                    require(p.remaining() >= ivSize) { "$context: truncated per-sample IV" }
                    ByteArray(ivSize).also { p.get(it) }
                } else null
                try {
                    val ivIdentity = rawIv?.let { ProtectedValueFingerprint.fromBytes("iv", it) }
                    val inspected = fragment.sampleEncryptionMetadata[index]
                    require(inspected.ivSize == ivSize && inspected.ivIdentity == ivIdentity) {
                        "$context: senc IV differs from the independently inspected sample IV identity"
                    }
                    require(p.remaining() >= 2) { "$context: truncated senc subsample count" }
                    val rangeCount = u16(p)
                    require(p.remaining().toLong() >= rangeCount.toLong() * 6L) {
                        "$context: truncated senc subsample table"
                    }
                    val parsedRanges = List(rangeCount) {
                        CmafBoxInspector.SubsampleEncryptionRange(u16(p), u32(p))
                    }
                    require(parsedRanges == inspected.subsampleRanges) {
                        "$context: senc subsample map differs from the independent CMAF inspection"
                    }
                    if (index in selectedIndices && rawIv != null) {
                        result[index] = rawIv.copyOf()
                    }
                } finally {
                    rawIv?.fill(0)
                }
            }
            require(!p.hasRemaining()) { "$context: unexpected trailing senc data" }
            return result
        } catch (error: Throwable) {
            result.values.forEach { it.fill(0) }
            throw error
        }
    }

    private fun parseBoxes(data: ByteArray, start: Int, end: Int): List<Box> {
        require(start >= 0 && end >= start && end <= data.size)
        val result = mutableListOf<Box>()
        var cursor = start
        var count = 0
        while (cursor < end) {
            require(end - cursor >= 8) { "Truncated ISO-BMFF box header" }
            require(++count <= MAX_BOXES) { "ISO-BMFF box count exceeds extraction limit" }
            val size32 = readU32(data, cursor)
            val type = String(data, cursor + 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            val size = when (size32) {
                0L -> (end - cursor).toLong()
                1L -> {
                    require(end - cursor >= 16) { "Truncated extended ISO-BMFF box header" }
                    headerSize = 16
                    val value = ByteBuffer.wrap(data, cursor + 8, 8).order(ByteOrder.BIG_ENDIAN).long
                    require(value >= 0L) { "ISO-BMFF box exceeds supported signed size" }
                    value
                }
                else -> size32
            }
            require(size >= headerSize.toLong() && size <= (end - cursor).toLong()) {
                "Invalid ISO-BMFF box size for $type"
            }
            require(size <= Int.MAX_VALUE.toLong()) { "ISO-BMFF box is too large" }
            result += Box(type, cursor, size.toInt(), headerSize)
            cursor = Math.addExact(cursor, size.toInt())
        }
        require(cursor == end) { "ISO-BMFF boxes do not exactly cover their parent" }
        return result
    }

    private fun children(data: ByteArray, parent: Box): List<Box> =
        parseBoxes(data, parent.payloadStart, parent.endExclusive)

    private fun payload(data: ByteArray, box: Box): ByteBuffer =
        ByteBuffer.wrap(data, box.payloadStart, box.size - box.headerSize)
            .slice().order(ByteOrder.BIG_ENDIAN)

    private fun readFlags(p: ByteBuffer): Int =
        (u8(p) shl 16) or (u8(p) shl 8) or u8(p)

    private fun skip(p: ByteBuffer, count: Int) {
        require(count >= 0 && p.remaining() >= count) { "Truncated ISO-BMFF field" }
        p.position(p.position() + count)
    }

    private fun u8(p: ByteBuffer): Int {
        require(p.hasRemaining()) { "Truncated ISO-BMFF byte" }
        return p.get().toInt() and 0xff
    }

    private fun u16(p: ByteBuffer): Int {
        require(p.remaining() >= 2) { "Truncated ISO-BMFF uint16" }
        return p.short.toInt() and 0xffff
    }

    private fun u32(p: ByteBuffer): Long {
        require(p.remaining() >= 4) { "Truncated ISO-BMFF uint32" }
        return p.int.toLong() and 0xffff_ffffL
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset <= data.size - 4)
        return ((data[offset].toLong() and 0xffL) shl 24) or
            ((data[offset + 1].toLong() and 0xffL) shl 16) or
            ((data[offset + 2].toLong() and 0xffL) shl 8) or
            (data[offset + 3].toLong() and 0xffL)
    }

    private const val MAX_INIT_BYTES = 8 * 1024 * 1024
    private const val MAX_FRAGMENT_BYTES = 8 * 1024 * 1024
    private const val MAX_SAMPLE_BYTES = 64 * 1024 * 1024
    private const val MAX_BOXES = 20_000
}
