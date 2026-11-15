package app.apexview.tiled

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bounded, read-only ISO-BMFF/CMAF metadata inspector.
 *
 * Input must be an init segment or a clear metadata-only fragment supplied by the caller.
 * This class never returns box payloads, sample bytes, URLs, KIDs, IVs, or DRM material.
 */
internal object CmafBoxInspector {
    data class Box(val type: String, val start: Int, val size: Int, val headerSize: Int, val children: List<Box>)
    data class HevcConfiguration(
        val configurationVersion: Int,
        val profileSpace: Int,
        val tierFlag: Boolean,
        val profileIdc: Int,
        val compatibilityFlags: Long,
        val constraintIndicatorFlags: Long,
        val levelIdc: Int,
        val minSpatialSegmentationIdc: Int,
        val parallelismType: Int,
        val chromaFormat: Int,
        val bitDepthLuma: Int,
        val bitDepthChroma: Int,
        val averageFrameRate: Int,
        val constantFrameRate: Int,
        val temporalLayers: Int,
        val temporalIdNested: Boolean,
        val nalLengthSize: Int,
        val vpsCount: Int,
        val spsCount: Int,
        val ppsCount: Int,
        val vps: List<HevcParameterSets.VpsMetadata>,
        val sps: List<HevcParameterSets.SpsMetadata>,
        val pps: List<HevcParameterSets.PpsMetadata>,
        val parameterSetErrors: List<String>
    )
    data class TrackEncryptionMetadata(
        val version: Int,
        val defaultIsProtected: Boolean,
        val perSampleIvSize: Int,
        val cryptByteBlock: Int,
        val skipByteBlock: Int,
        val constantIvSize: Int?
    )
    data class SampleTiming(
        val decodeTimeTicks: Long?,
        val presentationTimeTicks: Long?,
        val durationTicks: Long?,
        val compositionOffsetTicks: Long?
    )
    /** Byte-range shape only. No encrypted bytes, IV bytes or key identifiers are retained. */
    data class SubsampleEncryptionRange(
        val clearBytes: Int,
        val encryptedBytes: Long
    )
    data class SampleEncryptionMetadata(
        val ivSize: Int,
        val subsampleRanges: List<SubsampleEncryptionRange>?
    )
    data class FragmentMetadata(
        val trackId: Long,
        val timescale: Long?,
        val baseDecodeTimeTicks: Long?,
        val sampleCount: Int,
        val totalDurationTicks: Long?,
        val sampleTimings: List<SampleTiming>,
        val timingComplete: Boolean,
        val sampleEncryptionBoxPresent: Boolean,
        val subsampleEncryptionSignalled: Boolean?,
        val sampleEncryptionCount: Long?,
        val sampleAuxiliarySizesPresent: Boolean,
        val sampleAuxiliaryOffsetsPresent: Boolean,
        val sampleGroupDescriptionPresent: Boolean,
        val sampleToGroupPresent: Boolean,
        val durationIsEmpty: Boolean = false,
        val sampleEncryptionStructureComplete: Boolean = false,
        val sampleEncryptionMetadata: List<SampleEncryptionMetadata> = emptyList(),
        val sencOverrideIvSize: Int? = null
    )
    data class TrackMetadata(
        val trackId: Long?,
        val handler: String?,
        val timescale: Long?,
        val sampleEntry: String?,
        val originalFormat: String?,
        val protectionScheme: String?,
        val encrypted: Boolean,
        val width: Int?,
        val height: Int?,
        val hevcConfiguration: HevcConfiguration?,
        val trackEncryption: TrackEncryptionMetadata?,
        val sampleEncryptionBoxPresent: Boolean,
        val sampleAuxiliarySizesPresent: Boolean,
        val sampleAuxiliaryOffsetsPresent: Boolean,
        val sampleGroupDescriptionPresent: Boolean,
        val sampleToGroupPresent: Boolean
    )
    data class Report(
        val majorBrand: String?,
        val compatibleBrands: List<String>,
        val topLevelBoxes: List<String>,
        val tracks: List<TrackMetadata>,
        val fragments: List<FragmentMetadata>,
        val fragmented: Boolean,
        val errors: List<String>
    )

    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex",
        "moof", "traf", "mfra", "sinf", "schi", "udta", "meta", "ilst")
    private const val MAX_BOXES = 20_000
    private const val MAX_DEPTH = 12

    fun inspect(data: ByteArray): Report {
        require(data.size >= 8) { "Input is too small to contain an ISO-BMFF box" }
        val errors = mutableListOf<String>()
        val boxes = parseBoxes(data, 0, data.size, 0, intArrayOf(0), errors)
        val ftyp = boxes.firstOrNull { it.type == "ftyp" }?.let { payload(data, it) }
        val major = ftyp?.takeIf { it.remaining() >= 8 }?.let { readFourCc(it) }
        val brands = mutableListOf<String>()
        if (ftyp != null && ftyp.remaining() >= 4) {
            ftyp.position(ftyp.position() + 4)
            while (ftyp.remaining() >= 4) brands += readFourCc(ftyp)
        }
        val moov = boxes.firstOrNull { it.type == "moov" }
        val tracks = moov?.children?.filter { it.type == "trak" }?.map { track(data, it, errors) }.orEmpty()
        val trackTimescales = tracks.mapNotNull { item ->
            val id = item.trackId
            val scale = item.timescale
            if (id != null && scale != null) id to scale else null
        }.toMap()
        val trackEncryptionDefaults = tracks.mapNotNull { item ->
            val id = item.trackId
            val encryption = item.trackEncryption
            if (id != null && encryption != null) id to encryption else null
        }.toMap()
        val trexDefaults = moov?.let { parseTrexDefaults(data, it, errors) }.orEmpty()
        val fragments = boxes.filter { it.type == "moof" }.flatMap { moof ->
            moof.children.filter { it.type == "traf" }.mapNotNull { traf ->
                try {
                    parseFragmentTrack(data, traf, trexDefaults, trackTimescales,
                        trackEncryptionDefaults, errors)
                } catch (e: Exception) {
                    errors += "Fragment timing metadata could not be parsed: ${e.message ?: e.javaClass.simpleName}"
                    null
                }
            }
        }
        return Report(major, brands, boxes.map { it.type }, tracks, fragments,
            boxes.any { it.type == "moof" } || boxes.any { it.type == "moov" && it.children.any { c -> c.type == "mvex" } },
            errors)
    }

    private fun track(data: ByteArray, trak: Box, errors: MutableList<String>): TrackMetadata {
        val all = flatten(trak).toMutableList()
        val trackId = all.firstOrNull { it.type == "tkhd" }?.let { readTrackId(payload(data, it)) }
        val handler = all.firstOrNull { it.type == "hdlr" }?.let { payload(data, it) }?.let { p ->
            if (p.remaining() >= 12) { p.position(p.position() + 8); readFourCc(p) } else null
        }
        val stsdBox = all.firstOrNull { it.type == "stsd" }
        var sampleEntry: String? = null
        var width: Int? = null
        var height: Int? = null
        if (stsdBox != null) {
            val stsd = payload(data, stsdBox)
            // FullBox header (4) + entry_count (4) + sample_entry size (4).
            if (stsd.remaining() >= 16) {
                stsd.position(stsd.position() + 8)
                val entrySize = stsd.int.toLong() and 0xffffffffL
                sampleEntry = readFourCc(stsd)
                if (handler == "vide" && sampleEntry in setOf("avc1", "avc3", "hvc1", "hev1", "encv", "dvhe", "dvh1")) {
                    // The visual sample-entry fields begin after the 8-byte box header.
                    // Skip reserved[6], data_reference_index[2], pre_defined[2],
                    // reserved[2], and pre_defined[3][4] before width/height.
                    if (stsd.remaining() >= 28 && entrySize >= 8L + 28L) {
                        stsd.position(stsd.position() + 24)
                        width = stsd.short.toInt() and 0xffff
                        height = stsd.short.toInt() and 0xffff
                    }
                }
                val entryStart = stsdBox.start + stsdBox.headerSize + 8
                val entryEndLong = entryStart.toLong() + entrySize
                val boxEnd = stsdBox.start.toLong() + stsdBox.size
                if (entrySize >= 8 && entryEndLong <= boxEnd && entryEndLong <= data.size.toLong()) {
                    val entryEnd = entryEndLong.toInt()
                    val childStart = entryStart + 8 + if (handler == "vide" ||
                        sampleEntry in setOf("avc1", "avc3", "hvc1", "hev1", "encv", "dvhe", "dvh1")) 78 else 28
                    if (childStart <= entryEnd) {
                        val entryChildren = parseBoxes(data, childStart, entryEnd, 1, intArrayOf(0), errors)
                        all += entryChildren.flatMap { flatten(it) }
                    }
                }
            }
        }
        val mdhd = all.firstOrNull { it.type == "mdhd" }?.let { payload(data, it) }
        val timescale = mdhd?.let { readTimescale(it) }
        val original = all.firstOrNull { it.type == "frma" }?.let { payload(data, it) }?.takeIf { it.remaining() >= 4 }?.let { readFourCc(it) }
        val scheme = all.firstOrNull { it.type == "schm" }?.let { payload(data, it) }?.takeIf { it.remaining() >= 8 }?.let { p ->
            p.position(p.position() + 4); readFourCc(p)
        }
        val hevcBox = all.firstOrNull { it.type == "hvcC" }
        val hevcConfiguration = hevcBox?.let {
            try { parseHevcConfiguration(payload(data, it)) }
            catch (e: Exception) {
                errors += "HEVC decoder configuration could not be parsed: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
        val tencBox = all.firstOrNull { it.type == "tenc" }
        val trackEncryption = tencBox?.let {
            try { parseTrackEncryption(payload(data, it)) }
            catch (e: Exception) {
                errors += "Track encryption metadata could not be parsed: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
        return TrackMetadata(
            trackId, handler, timescale, sampleEntry, original, scheme,
            sampleEntry == "encv" || sampleEntry == "enca" || scheme != null, width, height,
            hevcConfiguration, trackEncryption,
            all.any { it.type == "senc" }, all.any { it.type == "saiz" }, all.any { it.type == "saio" },
            all.any { it.type == "sgpd" }, all.any { it.type == "sbgp" }
        )
    }

    private data class TfhdDefaults(
        val trackId: Long,
        val defaultSampleDuration: Long?,
        val durationIsEmpty: Boolean
    )

    private fun readTrackId(p: ByteBuffer): Long? {
        if (p.remaining() < 4) return null
        val version = u8(p)
        val creationAndModificationBytes = when (version) {
            0 -> 8
            1 -> 16
            else -> return null
        }
        if (p.remaining() < 3 + creationAndModificationBytes + 4) return null
        p.position(p.position() + 3) // FullBox flags
        p.position(p.position() + creationAndModificationBytes)
        return u32(p)
    }

    private fun parseTrexDefaults(
        data: ByteArray,
        moov: Box,
        errors: MutableList<String>
    ): Map<Long, Long> {
        val defaults = mutableMapOf<Long, Long>()
        flatten(moov).filter { it.type == "trex" }.forEach { box ->
            try {
                val p = payload(data, box)
                require(p.remaining() >= 24) { "Truncated trex box" }
                p.position(p.position() + 4) // FullBox header
                val trackId = u32(p)
                p.position(p.position() + 4) // default_sample_description_index
                defaults[trackId] = u32(p)
            } catch (e: Exception) {
                errors += "Track default duration could not be parsed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return defaults
    }

    private fun parseFragmentTrack(
        data: ByteArray,
        traf: Box,
        trexDefaults: Map<Long, Long>,
        timescales: Map<Long, Long>,
        trackEncryptionDefaults: Map<Long, TrackEncryptionMetadata>,
        errors: MutableList<String>
    ): FragmentMetadata {
        val tfhdBox = traf.children.firstOrNull { it.type == "tfhd" }
            ?: throw IllegalArgumentException("traf is missing tfhd")
        val tfhd = parseTfhd(payload(data, tfhdBox))
        val baseDecodeTime = traf.children.firstOrNull { it.type == "tfdt" }
            ?.let { parseTfdt(payload(data, it)) }
        val defaultDuration = tfhd.defaultSampleDuration ?: trexDefaults[tfhd.trackId]
        val truns = traf.children.filter { it.type == "trun" }
        if (tfhd.durationIsEmpty) {
            require(truns.isEmpty()) { "duration-is-empty traf must not contain trun boxes" }
        } else {
            require(truns.isNotEmpty()) { "traf is missing trun" }
        }
        val samples = mutableListOf<SampleTiming>()
        var decodeCursor = baseDecodeTime
        for (trun in truns) {
            decodeCursor = parseTrun(
                payload(data, trun),
                defaultDuration,
                decodeCursor,
                samples
            )
        }
        val totalDuration = if (samples.isNotEmpty() && samples.all { it.durationTicks != null }) {
            samples.fold(0L) { total, item -> Math.addExact(total, item.durationTicks!!) }
        } else null
        val sencBox = traf.children.firstOrNull { it.type == "senc" }
        val senc = sencBox?.let { box ->
            try {
                parseSencSummary(payload(data, box), trackEncryptionDefaults[tfhd.trackId])
            } catch (e: Exception) {
                errors += "Sample encryption structure could not be parsed: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
        return FragmentMetadata(
            trackId = tfhd.trackId,
            timescale = timescales[tfhd.trackId],
            baseDecodeTimeTicks = baseDecodeTime,
            sampleCount = samples.size,
            totalDurationTicks = totalDuration,
            sampleTimings = samples,
            timingComplete = !tfhd.durationIsEmpty && baseDecodeTime != null && samples.isNotEmpty() &&
                samples.all { it.decodeTimeTicks != null && it.durationTicks != null },
            sampleEncryptionBoxPresent = sencBox != null,
            subsampleEncryptionSignalled = senc?.subsampleEncryption,
            sampleEncryptionCount = senc?.sampleCount,
            sampleAuxiliarySizesPresent = traf.children.any { it.type == "saiz" },
            sampleAuxiliaryOffsetsPresent = traf.children.any { it.type == "saio" },
            sampleGroupDescriptionPresent = traf.children.any { it.type == "sgpd" },
            sampleToGroupPresent = traf.children.any { it.type == "sbgp" },
            durationIsEmpty = tfhd.durationIsEmpty,
            sampleEncryptionStructureComplete = senc?.structureComplete == true,
            sampleEncryptionMetadata = senc?.samples.orEmpty(),
            sencOverrideIvSize = senc?.overrideIvSize
        )
    }

    private fun parseTfhd(p: ByteBuffer): TfhdDefaults {
        require(p.remaining() >= 8) { "Truncated tfhd box" }
        val version = u8(p)
        require(version == 0) { "Unsupported tfhd version $version" }
        val flags = (u8(p) shl 16) or (u8(p) shl 8) or u8(p)
        val knownFlags = 0x000001 or 0x000002 or 0x000008 or 0x000010 or 0x000020 or
            0x010000 or 0x020000
        require((flags and knownFlags.inv()) == 0) { "Unsupported tfhd flags" }
        val trackId = u32(p)
        require(trackId > 0) { "Invalid tfhd track ID" }
        if ((flags and 0x000001) != 0) skip(p, 8) // base_data_offset
        if ((flags and 0x000002) != 0) skip(p, 4) // sample_description_index
        val defaultDuration = if ((flags and 0x000008) != 0) u32(p) else null
        if ((flags and 0x000010) != 0) skip(p, 4) // default_sample_size
        if ((flags and 0x000020) != 0) skip(p, 4) // default_sample_flags
        require(!p.hasRemaining()) { "Unexpected trailing bytes in tfhd box" }
        return TfhdDefaults(trackId, defaultDuration, (flags and 0x010000) != 0)
    }

    private fun parseTfdt(p: ByteBuffer): Long {
        require(p.remaining() >= 8) { "Truncated tfdt box" }
        val version = u8(p)
        val flags = (u8(p) shl 16) or (u8(p) shl 8) or u8(p)
        require(flags == 0) { "Unsupported tfdt flags" }
        val time = when (version) {
            0 -> u32(p)
            1 -> readUnsignedLong(p)
            else -> throw IllegalArgumentException("Unsupported tfdt version $version")
        }
        require(!p.hasRemaining()) { "Unexpected trailing bytes in tfdt box" }
        return time
    }

    private fun parseTrun(
        p: ByteBuffer,
        defaultDuration: Long?,
        initialDecodeTime: Long?,
        output: MutableList<SampleTiming>
    ): Long? {
        require(p.remaining() >= 8) { "Truncated trun box" }
        val version = u8(p)
        require(version in 0..1) { "Unsupported trun version $version" }
        val flags = (u8(p) shl 16) or (u8(p) shl 8) or u8(p)
        val knownFlags = 0x000001 or 0x000004 or 0x000100 or 0x000200 or 0x000400 or 0x000800
        require((flags and knownFlags.inv()) == 0) { "Unsupported trun flags" }
        val countLong = u32(p)
        require(countLong <= MAX_FRAGMENT_SAMPLES.toLong() - output.size) {
            "Fragment sample count exceeds limit"
        }
        val count = countLong.toInt()
        if ((flags and 0x000001) != 0) skip(p, 4) // data_offset
        if ((flags and 0x000004) != 0) skip(p, 4) // first_sample_flags
        var cursor = initialDecodeTime
        repeat(count) {
            val duration = if ((flags and 0x000100) != 0) u32(p) else defaultDuration
            if ((flags and 0x000200) != 0) skip(p, 4) // sample_size
            if ((flags and 0x000400) != 0) skip(p, 4) // sample_flags
            val compositionOffset = if ((flags and 0x000800) != 0) {
                if (version == 1) readSignedInt(p) else u32(p)
            } else 0L
            val presentationTime = cursor?.let { Math.addExact(it, compositionOffset) }
            output += SampleTiming(cursor, presentationTime, duration, compositionOffset)
            cursor = if (cursor != null && duration != null) Math.addExact(cursor, duration) else null
        }
        require(!p.hasRemaining()) { "Unexpected trailing bytes in trun box" }
        return cursor
    }

    private data class SencSummary(
        val sampleCount: Long,
        val subsampleEncryption: Boolean,
        val structureComplete: Boolean,
        val samples: List<SampleEncryptionMetadata>,
        val overrideIvSize: Int?
    )

    private fun parseSencSummary(
        p: ByteBuffer,
        trackEncryption: TrackEncryptionMetadata?
    ): SencSummary {
        require(p.remaining() >= 8) { "Truncated senc box" }
        val version = u8(p)
        require(version == 0) { "Unsupported senc version $version" }
        val flags = (u8(p) shl 16) or (u8(p) shl 8) or u8(p)
        require((flags and 0xfffffc) == 0) { "Unsupported senc flags" }
        var overrideIvSize: Int? = null
        if ((flags and 0x000001) != 0) {
            skip(p, 3) // algorithm_id: not needed for structural analysis
            overrideIvSize = u8(p)
            require(overrideIvSize == 0 || overrideIvSize == 8 || overrideIvSize == 16) {
                "Unsupported senc override IV size"
            }
            skip(p, 16) // Skip the KID. Never copy, compare, retain, or log its bytes.
        }
        val sampleCount = u32(p)
        require(sampleCount <= MAX_FRAGMENT_SAMPLES.toLong()) {
            "senc sample count exceeds limit"
        }
        val subsampleEncryption = (flags and 0x000002) != 0

        // Without tenc defaults or an explicit senc override, the IV byte count cannot be
        // established safely. Preserve only the declared count and fail the crypto check closed.
        val defaultIvSize = trackEncryption?.let {
            if (it.defaultIsProtected) it.perSampleIvSize else 0
        }
        val effectiveIvSize = overrideIvSize ?: defaultIvSize
            ?: return SencSummary(sampleCount, subsampleEncryption, false, emptyList(), null)

        val samples = ArrayList<SampleEncryptionMetadata>(sampleCount.toInt())
        var totalSubsampleEntries = 0L
        repeat(sampleCount.toInt()) {
            if (effectiveIvSize > 0) skip(p, effectiveIvSize) // Skip IV bytes without retaining them.
            val ranges: List<SubsampleEncryptionRange>? = if (subsampleEncryption) {
                val count = u16(p)
                totalSubsampleEntries = Math.addExact(totalSubsampleEntries, count.toLong())
                require(totalSubsampleEntries <= MAX_TOTAL_SUBSAMPLE_ENTRIES) {
                    "senc subsample entry count exceeds limit"
                }
                val entries = ArrayList<SubsampleEncryptionRange>(count)
                repeat(count) {
                    val clearBytes = u16(p)
                    val encryptedBytes = u32(p)
                    entries.add(SubsampleEncryptionRange(clearBytes, encryptedBytes))
                }
                entries
            } else null
            samples += SampleEncryptionMetadata(effectiveIvSize, ranges)
        }
        require(!p.hasRemaining()) { "Unexpected trailing bytes in senc box" }
        return SencSummary(sampleCount, subsampleEncryption, true, samples, overrideIvSize)
    }

    private fun skip(p: ByteBuffer, count: Int) {
        require(count >= 0 && p.remaining() >= count) { "Truncated ISO-BMFF field" }
        p.position(p.position() + count)
    }

    private fun readUnsignedLong(p: ByteBuffer): Long {
        require(p.remaining() >= 8) { "Truncated 64-bit time field" }
        val value = p.long
        require(value >= 0) { "64-bit media time exceeds supported signed range" }
        return value
    }

    private fun readSignedInt(p: ByteBuffer): Long {
        require(p.remaining() >= 4) { "Truncated signed composition offset" }
        return p.int.toLong()
    }

    private const val MAX_FRAGMENT_SAMPLES = 100_000
    private const val MAX_TOTAL_SUBSAMPLE_ENTRIES = 1_000_000L

    private fun parseHevcConfiguration(p: ByteBuffer): HevcConfiguration {
        require(p.remaining() >= 23) { "Truncated hvcC record" }
        val version = u8(p)
        require(version == 1) { "Unsupported hvcC configuration version $version" }
        val profileByte = u8(p)
        val profileSpace = profileByte ushr 6
        val tierFlag = ((profileByte ushr 5) and 1) != 0
        val profileIdc = profileByte and 0x1f
        val compatibility = u32(p)
        val constraints = (u32(p) shl 16) or u16(p).toLong()
        val level = u8(p)
        val segmentationField = u16(p)
        require((segmentationField and 0xf000) == 0xf000) { "Invalid hvcC spatial segmentation reserved bits" }
        val parallelismField = u8(p)
        require((parallelismField and 0xfc) == 0xfc) { "Invalid hvcC parallelism reserved bits" }
        val chromaField = u8(p)
        require((chromaField and 0xfc) == 0xfc) { "Invalid hvcC chroma reserved bits" }
        val lumaDepthField = u8(p)
        require((lumaDepthField and 0xf8) == 0xf8) { "Invalid hvcC luma bit-depth reserved bits" }
        val chromaDepthField = u8(p)
        require((chromaDepthField and 0xf8) == 0xf8) { "Invalid hvcC chroma bit-depth reserved bits" }
        val averageFrameRate = u16(p)
        val packed = u8(p)
        val constantFrameRate = packed ushr 6
        val temporalLayers = (packed ushr 3) and 0x07
        val temporalIdNested = ((packed ushr 2) and 1) != 0
        val nalLengthSize = (packed and 0x03) + 1
        val arrayCount = u8(p)
        require(arrayCount <= 64) { "hvcC contains too many NAL arrays" }

        val vpsNals = mutableListOf<ByteArray>()
        val spsNals = mutableListOf<ByteArray>()
        val ppsNals = mutableListOf<ByteArray>()
        repeat(arrayCount) {
            require(p.remaining() >= 3) { "Truncated hvcC NAL array header" }
            val arrayHeader = u8(p)
            require((arrayHeader and 0x40) == 0) { "Invalid hvcC NAL array reserved bit" }
            val declaredType = arrayHeader and 0x3f
            val nalCount = u16(p)
            require(nalCount <= 1024) { "hvcC NAL array contains too many entries" }
            repeat(nalCount) {
                require(p.remaining() >= 2) { "Truncated hvcC NAL length" }
                val nalLength = u16(p)
                require(nalLength >= 2 && nalLength <= p.remaining()) { "Invalid hvcC NAL length" }
                val nal = ByteArray(nalLength)
                p.get(nal)
                val header = HevcBitstream.parseNalHeader(nal)
                require(header.type == declaredType) { "hvcC array type does not match its NAL header" }
                when (declaredType) {
                    32 -> vpsNals += nal
                    33 -> spsNals += nal
                    34 -> ppsNals += nal
                }
            }
        }
        require(!p.hasRemaining()) { "Unexpected trailing bytes in hvcC record" }

        val parameterErrors = mutableListOf<String>()
        val vps = vpsNals.mapNotNull { nal ->
            try { HevcParameterSets.parseVps(nal) }
            catch (e: Exception) { parameterErrors += "VPS parse failed: ${e.message ?: e.javaClass.simpleName}"; null }
        }
        val sps = spsNals.mapNotNull { nal ->
            try { HevcParameterSets.parseSps(nal) }
            catch (e: Exception) { parameterErrors += "SPS parse failed: ${e.message ?: e.javaClass.simpleName}"; null }
        }
        val pps = ppsNals.mapNotNull { nal ->
            try {
                val preliminary = HevcParameterSets.parsePps(nal)
                val matchingSps = sps.firstOrNull { it.spsId == preliminary.spsId }
                if (matchingSps == null) {
                    parameterErrors += "PPS ${preliminary.ppsId} has no matching parsed SPS"
                    preliminary
                } else HevcParameterSets.parsePps(nal, matchingSps)
            } catch (e: Exception) {
                parameterErrors += "PPS parse failed: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
        return HevcConfiguration(
            version, profileSpace, tierFlag, profileIdc, compatibility, constraints, level,
            segmentationField and 0x0fff, parallelismField and 0x03, chromaField and 0x03,
            (lumaDepthField and 0x07) + 8, (chromaDepthField and 0x07) + 8,
            averageFrameRate, constantFrameRate, temporalLayers, temporalIdNested, nalLengthSize,
            vpsNals.size, spsNals.size, ppsNals.size, vps, sps, pps, parameterErrors
        )
    }

    /**
     * Reads only the track-level encryption shape. KIDs, constant IV bytes and all actual
     * sample-encryption payloads are skipped and never returned or logged.
     */
    private fun parseTrackEncryption(p: ByteBuffer): TrackEncryptionMetadata {
        require(p.remaining() >= 4 + 2 + 2 + 16) { "Truncated tenc box" }
        val version = u8(p)
        p.get(); p.get(); p.get() // FullBox flags
        require(version in 0..1) { "Unsupported tenc version $version" }
        val reserved = u8(p)
        require(reserved == 0) { "Invalid tenc reserved byte" }
        val pattern = u8(p)
        val cryptBlocks = if (version == 1) pattern ushr 4 else 0
        val skipBlocks = if (version == 1) pattern and 0x0f else 0
        if (version == 0) require(pattern == 0) { "Invalid version 0 tenc reserved byte" }
        val isProtected = u8(p)
        val ivSize = u8(p)
        require(isProtected in 0..1) { "Invalid tenc default_isProtected value" }
        require(ivSize == 0 || ivSize == 8 || ivSize == 16) { "Unsupported tenc per-sample IV size" }
        p.position(p.position() + 16) // Skip default_KID without retaining it.
        var constantIvSize: Int? = null
        if (isProtected == 1 && ivSize == 0) {
            require(p.hasRemaining()) { "Missing tenc constant IV size" }
            constantIvSize = u8(p)
            require(constantIvSize == 8 || constantIvSize == 16) { "Unsupported tenc constant IV size" }
            require(p.remaining() >= constantIvSize) { "Truncated tenc constant IV" }
            p.position(p.position() + constantIvSize) // Skip constant IV bytes.
        }
        require(!p.hasRemaining()) { "Unexpected trailing bytes in tenc box" }
        return TrackEncryptionMetadata(version, isProtected == 1, ivSize, cryptBlocks, skipBlocks, constantIvSize)
    }

    private fun u8(p: ByteBuffer): Int {
        require(p.hasRemaining()) { "Unexpected end of ISO-BMFF field" }
        return p.get().toInt() and 0xff
    }

    private fun u16(p: ByteBuffer): Int {
        require(p.remaining() >= 2) { "Unexpected end of ISO-BMFF 16-bit field" }
        return p.short.toInt() and 0xffff
    }

    private fun u32(p: ByteBuffer): Long {
        require(p.remaining() >= 4) { "Unexpected end of ISO-BMFF 32-bit field" }
        return p.int.toLong() and 0xffffffffL
    }

    private fun readTimescale(p: ByteBuffer): Long? {
        if (p.remaining() < 4) return null
        val version = p.get(p.position()).toInt() and 0xff
        val offset = if (version == 1) 20 else 12
        if (p.remaining() < offset + 4) return null
        p.position(p.position() + offset)
        return p.int.toLong() and 0xffffffffL
    }

    private fun parseBoxes(data: ByteArray, start: Int, end: Int, depth: Int, count: IntArray,
                           errors: MutableList<String>): List<Box> {
        require(depth <= MAX_DEPTH) { "ISO-BMFF nesting exceeds limit" }
        val result = mutableListOf<Box>()
        var pos = start
        while (pos < end) {
            if (end - pos < 8) { errors += "Trailing bytes after final complete box"; break }
            if (++count[0] > MAX_BOXES) throw IllegalArgumentException("ISO-BMFF box count exceeds limit")
            val size32 = uint32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            val size = when (size32) {
                0L -> (end - pos).toLong()
                1L -> {
                    if (end - pos < 16) throw IllegalArgumentException("Truncated extended box header")
                    header = 16
                    uint64(data, pos + 8)
                }
                else -> size32
            }
            if (size < header || size > (end - pos).toLong()) throw IllegalArgumentException("Invalid box size for $type")
            val boxEnd = pos + size.toInt()
            var childStart = pos + header
            if (type == "meta") childStart += 4
            val children = if (type in containers && childStart <= boxEnd)
                parseBoxes(data, childStart, boxEnd, depth + 1, count, errors) else emptyList()
            result += Box(type, pos, size.toInt(), header, children)
            pos = boxEnd
        }
        return result
    }

    private fun flatten(box: Box): List<Box> = listOf(box) + box.children.flatMap { flatten(it) }
    private fun payload(data: ByteArray, box: Box): ByteBuffer =
        ByteBuffer.wrap(data, box.start + box.headerSize, box.size - box.headerSize).slice().order(ByteOrder.BIG_ENDIAN)
    private fun uint32(data: ByteArray, p: Int): Long =
        ((data[p].toLong() and 255) shl 24) or ((data[p+1].toLong() and 255) shl 16) or
            ((data[p+2].toLong() and 255) shl 8) or (data[p+3].toLong() and 255)
    private fun uint64(data: ByteArray, p: Int): Long {
        val hi = uint32(data, p)
        val lo = uint32(data, p + 4)
        require(hi <= Int.MAX_VALUE.toLong()) { "Box size exceeds supported signed range" }
        return (hi shl 32) or lo
    }
    private fun readFourCc(p: ByteBuffer): String {
        val bytes = ByteArray(4); p.get(bytes); return String(bytes, Charsets.ISO_8859_1)
    }
}
