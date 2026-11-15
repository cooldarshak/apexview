package app.apexview.tiled

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

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
        val parameterSetErrors: List<String>,
        /** Sorted SHA-256 fingerprints of raw VPS/SPS/PPS NALs; never sample or DRM bytes. */
        val parameterSetFingerprints: List<String> = emptyList()
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
        val compositionOffsetTicks: Long?,
        val sampleSizeBytes: Long? = null
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
    /** Structural auxiliary-info shape only; encrypted bytes and IV/KID values are never retained. */
    data class SampleAuxiliaryMetadata(
        val auxiliaryInfoType: String?,
        val auxiliaryInfoParameter: Long?,
        val saizSampleCount: Long,
        val defaultSampleInfoSize: Int,
        val sampleInfoSizes: List<Int>,
        val saioOffsets: List<Long>
    )
    data class SeigProtectionEntry(
        val entryIndex: Int,
        val isProtected: Boolean,
        val perSampleIvSize: Int,
        val cryptByteBlock: Int,
        val skipByteBlock: Int,
        val constantIvSize: Int?
    )
    data class SampleGroupDescriptionMetadata(
        val version: Int,
        val defaultSampleDescriptionIndex: Long,
        val entries: List<SeigProtectionEntry>
    )
    data class SampleGroupRun(
        val sampleCount: Long,
        val groupDescriptionIndex: Long
    )
    data class SampleGroupEncryptionMetadata(
        val descriptionVersion: Int?,
        val defaultSampleDescriptionIndex: Long,
        val entries: List<SeigProtectionEntry>,
        val runs: List<SampleGroupRun>
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
        val sencOverrideIvSize: Int? = null,
        val sampleEncryptionRangesCoverSamples: Boolean = false,
        val sampleAuxiliaryInfoStructureComplete: Boolean = false,
        val sampleAuxiliaryMetadata: SampleAuxiliaryMetadata? = null,
        val sampleGroupEncryptionPresent: Boolean = false,
        val sampleGroupEncryptionStructureComplete: Boolean = true,
        val sampleGroupEncryptionMetadata: SampleGroupEncryptionMetadata? = null
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
        val sampleToGroupPresent: Boolean,
        val seigSampleGroupDescription: SampleGroupDescriptionMetadata? = null,
        val seigSampleGroupDescriptionStructureComplete: Boolean = true
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
        val trackMetadataById = tracks.mapNotNull { item ->
            item.trackId?.let { it to item }
        }.toMap()
        val trexDefaults = moov?.let { parseTrexDefaults(data, it, errors) }.orEmpty()
        val fragments = boxes.filter { it.type == "moof" }.flatMap { moof ->
            moof.children.filter { it.type == "traf" }.mapNotNull { traf ->
                try {
                    parseFragmentTrack(data, traf, trexDefaults, trackTimescales,
                        trackEncryptionDefaults, trackMetadataById, errors)
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
        val trackSeigDescriptions = mutableListOf<SampleGroupDescriptionMetadata>()
        var trackSeigStructureComplete = true
        all.filter { it.type == "sgpd" }.forEach { box ->
            try {
                parseSeigSgpd(payload(data, box))?.let { description ->
                    trackSeigDescriptions.add(
                        SampleGroupDescriptionMetadata(
                            version = description.version,
                            defaultSampleDescriptionIndex = description.defaultSampleDescriptionIndex,
                            entries = description.entries
                        )
                    )
                }
            } catch (e: Exception) {
                trackSeigStructureComplete = false
                errors += "Track-level seig sample-group descriptions could not be parsed"
            }
        }
        if (trackSeigDescriptions.size > 1) {
            trackSeigStructureComplete = false
            errors += "Multiple track-level seig sample-group descriptions are not supported"
        }
        return TrackMetadata(
            trackId, handler, timescale, sampleEntry, original, scheme,
            sampleEntry == "encv" || sampleEntry == "enca" || scheme != null, width, height,
            hevcConfiguration, trackEncryption,
            all.any { it.type == "senc" }, all.any { it.type == "saiz" }, all.any { it.type == "saio" },
            all.any { it.type == "sgpd" }, all.any { it.type == "sbgp" },
            trackSeigDescriptions.singleOrNull(), trackSeigStructureComplete
        )
    }

    private data class TfhdDefaults(
        val trackId: Long,
        val defaultSampleDuration: Long?,
        val defaultSampleSize: Long?,
        val durationIsEmpty: Boolean
    )

    private data class TrexDefaults(
        val defaultSampleDuration: Long?,
        val defaultSampleSize: Long?
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
    ): Map<Long, TrexDefaults> {
        val defaults = mutableMapOf<Long, TrexDefaults>()
        flatten(moov).filter { it.type == "trex" }.forEach { box ->
            try {
                val p = payload(data, box)
                require(p.remaining() >= 24) { "Truncated trex box" }
                val version = u8(p)
                val flags = (u8(p) shl 16) or (u8(p) shl 8) or u8(p)
                require(version == 0 && flags == 0) { "Unsupported trex FullBox header" }
                val trackId = u32(p)
                require(trackId > 0) { "Invalid trex track ID" }
                skip(p, 4) // default_sample_description_index
                val defaultDuration = u32(p).takeIf { it > 0 }
                val defaultSize = u32(p).takeIf { it > 0 }
                skip(p, 4) // default_sample_flags
                require(!p.hasRemaining()) { "Unexpected trailing bytes in trex box" }
                defaults[trackId] = TrexDefaults(defaultDuration, defaultSize)
            } catch (e: Exception) {
                errors += "Track defaults could not be parsed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return defaults
    }

    private fun parseFragmentTrack(
        data: ByteArray,
        traf: Box,
        trexDefaults: Map<Long, TrexDefaults>,
        timescales: Map<Long, Long>,
        trackEncryptionDefaults: Map<Long, TrackEncryptionMetadata>,
        trackMetadataById: Map<Long, TrackMetadata>,
        errors: MutableList<String>
    ): FragmentMetadata {
        val tfhdBox = traf.children.firstOrNull { it.type == "tfhd" }
            ?: throw IllegalArgumentException("traf is missing tfhd")
        val tfhd = parseTfhd(payload(data, tfhdBox))
        val baseDecodeTime = traf.children.firstOrNull { it.type == "tfdt" }
            ?.let { parseTfdt(payload(data, it)) }
        val trex = trexDefaults[tfhd.trackId]
        val defaultDuration = tfhd.defaultSampleDuration ?: trex?.defaultSampleDuration
        val defaultSampleSize = tfhd.defaultSampleSize ?: trex?.defaultSampleSize
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
                defaultSampleSize,
                decodeCursor,
                samples
            )
        }
        val totalDuration = if (samples.isNotEmpty() && samples.all { it.durationTicks != null }) {
            samples.fold(0L) { total, item -> Math.addExact(total, item.durationTicks!!) }
        } else null
        val sampleGroupEncryption = try {
            parseSampleGroupEncryption(
                data, traf, samples.size, trackMetadataById[tfhd.trackId], errors
            )
        } catch (e: Exception) {
            errors += "Sample-group encryption metadata could not be parsed: ${e.message ?: e.javaClass.simpleName}"
            SampleGroupParseResult(present = true, complete = false, metadata = null)
        }
        val sencBox = traf.children.firstOrNull { it.type == "senc" }
        val senc = sencBox?.let { box ->
            try {
                parseSencSummary(
                    payload(data, box),
                    trackEncryptionDefaults[tfhd.trackId],
                    forceIncomplete = sampleGroupEncryption.present
                )
            } catch (e: Exception) {
                errors += "Sample encryption structure could not be parsed: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
        val saizBox = traf.children.firstOrNull { it.type == "saiz" }
        val saioBox = traf.children.firstOrNull { it.type == "saio" }
        val saiz = saizBox?.let { box ->
            try { parseSaiz(payload(data, box)) }
            catch (e: Exception) {
                errors += "Sample auxiliary size metadata could not be parsed: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
        val saio = saioBox?.let { box ->
            try { parseSaio(payload(data, box)) }
            catch (e: Exception) {
                errors += "Sample auxiliary offset metadata could not be parsed: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
        val sencAuxiliarySizesMatch = if (sencBox != null && saiz != null && senc?.structureComplete == true) {
            senc.sampleCount == saiz.sampleCount &&
                senc.samples.size == saiz.sampleInfoSizes.size &&
                senc.samples.indices.all { index ->
                    val sample = senc.samples[index]
                    val expectedSize = sample.ivSize.toLong() +
                        (sample.subsampleRanges?.let { 2L + 6L * it.size } ?: 0L)
                    expectedSize == saiz.sampleInfoSizes[index].toLong()
                }
        } else true
        val sampleAuxiliaryInfoStructureComplete =
            saiz != null && saio != null &&
                saiz.sampleCount == samples.size.toLong() &&
                (saio.offsets.size == 1 || saio.offsets.size == samples.size) &&
                saiz.auxiliaryInfoType == saio.auxiliaryInfoType &&
                saiz.auxiliaryInfoParameter == saio.auxiliaryInfoParameter &&
                sencAuxiliarySizesMatch
        if ((saizBox != null || saioBox != null) && !sampleAuxiliaryInfoStructureComplete) {
            errors += "saiz/saio structure is incomplete or inconsistent with the fragment sample/encryption metadata"
        }
        val sampleAuxiliaryMetadata = if (saiz != null && saio != null) {
            SampleAuxiliaryMetadata(
                saiz.auxiliaryInfoType,
                saiz.auxiliaryInfoParameter,
                saiz.sampleCount,
                saiz.defaultSampleInfoSize,
                saiz.sampleInfoSizes,
                saio.offsets
            )
        } else null
        val sampleEncryptionRangesCoverSamples = senc?.let { summary ->
            if (!summary.structureComplete || summary.sampleCount != samples.size.toLong()) {
                false
            } else if (!summary.subsampleEncryption) {
                true
            } else {
                summary.samples.size == samples.size && summary.samples.indices.all { index ->
                    val sampleSize = samples[index].sampleSizeBytes ?: return@all false
                    val ranges = summary.samples[index].subsampleRanges ?: return@all false
                    val coveredBytes = ranges.fold(0L) { total, range ->
                        Math.addExact(total, Math.addExact(range.clearBytes.toLong(), range.encryptedBytes))
                    }
                    coveredBytes == sampleSize
                }
            }
        } == true
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
            sencOverrideIvSize = senc?.overrideIvSize,
            sampleEncryptionRangesCoverSamples = sampleEncryptionRangesCoverSamples,
            sampleAuxiliaryInfoStructureComplete = sampleAuxiliaryInfoStructureComplete,
            sampleAuxiliaryMetadata = sampleAuxiliaryMetadata,
            sampleGroupEncryptionPresent = sampleGroupEncryption.present,
            sampleGroupEncryptionStructureComplete = sampleGroupEncryption.complete,
            sampleGroupEncryptionMetadata = sampleGroupEncryption.metadata
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
        val defaultDuration = if ((flags and 0x000008) != 0) u32(p).takeIf { it > 0 } else null
        val defaultSampleSize = if ((flags and 0x000010) != 0) u32(p).takeIf { it > 0 } else null
        if ((flags and 0x000020) != 0) skip(p, 4) // default_sample_flags
        require(!p.hasRemaining()) { "Unexpected trailing bytes in tfhd box" }
        return TfhdDefaults(trackId, defaultDuration, defaultSampleSize, (flags and 0x010000) != 0)
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
        defaultSampleSize: Long?,
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
            val sampleSize = if ((flags and 0x000200) != 0) u32(p) else defaultSampleSize
            if ((flags and 0x000400) != 0) skip(p, 4) // sample_flags
            val compositionOffset = if ((flags and 0x000800) != 0) {
                if (version == 1) readSignedInt(p) else u32(p)
            } else 0L
            val presentationTime = cursor?.let { Math.addExact(it, compositionOffset) }
            output += SampleTiming(cursor, presentationTime, duration, compositionOffset, sampleSize)
            cursor = if (cursor != null && duration != null) Math.addExact(cursor, duration) else null
        }
        require(!p.hasRemaining()) { "Unexpected trailing bytes in trun box" }
        return cursor
    }

    private data class SaizMetadata(
        val auxiliaryInfoType: String?,
        val auxiliaryInfoParameter: Long?,
        val defaultSampleInfoSize: Int,
        val sampleCount: Long,
        val sampleInfoSizes: List<Int>
    )

    private data class SaioMetadata(
        val auxiliaryInfoType: String?,
        val auxiliaryInfoParameter: Long?,
        val offsets: List<Long>
    )

    private fun parseSaiz(p: ByteBuffer): SaizMetadata {
        require(p.remaining() >= 9) { "Truncated saiz box" }
        val version = u8(p)
        require(version == 0) { "Unsupported saiz version $version" }
        val flags = (u8(p) shl 16) or (u8(p) shl 8) or u8(p)
        require((flags and 0xfffffe) == 0) { "Unsupported saiz flags" }
        val auxiliaryInfoType = if ((flags and 1) != 0) readFourCc(p) else null
        val auxiliaryInfoParameter = if ((flags and 1) != 0) u32(p) else null
        val defaultSize = u8(p)
        val count = u32(p)
        require(count <= MAX_FRAGMENT_SAMPLES.toLong()) { "saiz sample count exceeds limit" }
        val sizes = if (defaultSize == 0) {
            List(count.toInt()) { u8(p) }
        } else {
            List(count.toInt()) { defaultSize }
        }
        require(!p.hasRemaining()) { "Unexpected trailing bytes in saiz box" }
        return SaizMetadata(auxiliaryInfoType, auxiliaryInfoParameter, defaultSize, count, sizes)
    }

    private fun parseSaio(p: ByteBuffer): SaioMetadata {
        require(p.remaining() >= 8) { "Truncated saio box" }
        val version = u8(p)
        require(version in 0..1) { "Unsupported saio version $version" }
        val flags = (u8(p) shl 16) or (u8(p) shl 8) or u8(p)
        require((flags and 0xfffffe) == 0) { "Unsupported saio flags" }
        val auxiliaryInfoType = if ((flags and 1) != 0) readFourCc(p) else null
        val auxiliaryInfoParameter = if ((flags and 1) != 0) u32(p) else null
        val count = u32(p)
        require(count <= MAX_FRAGMENT_SAMPLES.toLong()) { "saio entry count exceeds limit" }
        val offsets = List(count.toInt()) {
            if (version == 0) u32(p) else readUnsignedLong(p)
        }
        require(!p.hasRemaining()) { "Unexpected trailing bytes in saio box" }
        return SaioMetadata(auxiliaryInfoType, auxiliaryInfoParameter, offsets)
    }

    private data class ParsedSeigDescription(
        val version: Int,
        val defaultSampleDescriptionIndex: Long,
        val entries: List<SeigProtectionEntry>
    )

    private data class ParsedSeigMapping(
        val runs: List<SampleGroupRun>
    )

    private data class SampleGroupParseResult(
        val present: Boolean,
        val complete: Boolean,
        val metadata: SampleGroupEncryptionMetadata?
    )

    private fun parseSampleGroupEncryption(
        data: ByteArray,
        traf: Box,
        sampleCount: Int,
        trackMetadata: TrackMetadata?,
        errors: MutableList<String>
    ): SampleGroupParseResult {
        val sgpdBoxes = traf.children.filter { it.type == "sgpd" }
        val sbgpBoxes = traf.children.filter { it.type == "sbgp" }
        var parseFailed = false
        val descriptions = sgpdBoxes.mapNotNull { box ->
            try { parseSeigSgpd(payload(data, box)) }
            catch (e: Exception) {
                parseFailed = true
                errors += "seig sample-group descriptions could not be parsed"
                null
            }
        }
        val mappings = sbgpBoxes.mapNotNull { box ->
            try { parseSeigSbgp(payload(data, box)) }
            catch (e: Exception) {
                parseFailed = true
                errors += "seig sample-to-group mapping could not be parsed"
                null
            }
        }
        val present = descriptions.isNotEmpty() || mappings.isNotEmpty()
        if (parseFailed) return SampleGroupParseResult(true, false, null)
        if (!present) return SampleGroupParseResult(false, true, null)
        if (descriptions.size > 1 || mappings.size > 1) {
            errors += "Multiple seig sample-group boxes are not supported"
            return SampleGroupParseResult(true, false, null)
        }

        val description = descriptions.singleOrNull()
        val mapping = mappings.singleOrNull()
        val localEntries = description?.entries.orEmpty()
        val trackDescription = trackMetadata?.seigSampleGroupDescription
        val trackDescriptionComplete = trackMetadata?.seigSampleGroupDescriptionStructureComplete ?: true
        val trackEntries = trackDescription?.entries.orEmpty()
        val localDefaultIndex = description?.defaultSampleDescriptionIndex ?: 0L
        val trackDefaultIndex = trackDescription?.defaultSampleDescriptionIndex ?: 0L
        if (!trackDescriptionComplete) {
            errors += "Track-level seig sample-group descriptions are incomplete"
            return SampleGroupParseResult(true, false, null)
        }
        if (localDefaultIndex > localEntries.size.toLong() ||
            trackDefaultIndex > trackEntries.size.toLong()) {
            errors += "seig default sample-description index is out of range"
            return SampleGroupParseResult(true, false, null)
        }

        // sgpd in traf is fragment-local; its sbgp indices occupy the 0x10001+ range.
        // Lower 1-based indices refer to sgpd entries in the track's stbl.
        val combinedEntries = buildList {
            trackEntries.forEachIndexed { index, entry ->
                add(entry.copy(entryIndex = index + 1))
            }
            localEntries.forEachIndexed { index, entry ->
                add(entry.copy(entryIndex = 0x10001 + index))
            }
        }
        val runs = mapping?.runs?.toMutableList() ?: mutableListOf()
        if (mapping == null && sampleCount > 0) {
            val defaultReference = when {
                localDefaultIndex > 0 -> 0x10000L + localDefaultIndex
                trackDefaultIndex > 0 -> trackDefaultIndex
                else -> 0L
            }
            if (defaultReference > 0) runs += SampleGroupRun(sampleCount.toLong(), defaultReference)
        }
        if (mapping != null && runs.isEmpty() && sampleCount > 0) {
            errors += "seig sample-to-group mapping is empty"
            return SampleGroupParseResult(true, false, null)
        }

        var mappedSamples = 0L
        val resolvedRuns = mutableListOf<SampleGroupRun>()
        for (run in runs) {
            require(run.sampleCount > 0) { "seig sample-to-group run has zero samples" }
            mappedSamples = Math.addExact(mappedSamples, run.sampleCount)
            require(mappedSamples <= MAX_FRAGMENT_SAMPLES.toLong()) {
                "seig sample-to-group sample count exceeds limit"
            }
            val index = run.groupDescriptionIndex
            if (index == 0L) {
                resolvedRuns += run
            } else if (index in 1L..0xffffL) {
                require(index <= trackEntries.size.toLong()) {
                    "seig mapping references a missing track-level description"
                }
                resolvedRuns += run
            } else if (index in 0x10001L..0x1ffffL) {
                val localIndex = index - 0x10000L
                require(localIndex <= localEntries.size.toLong()) {
                    "seig mapping references a missing fragment-local description"
                }
                resolvedRuns += run
            } else {
                throw IllegalArgumentException("Unsupported seig group-description index scope")
            }
        }
        if (mapping != null && mappedSamples != sampleCount.toLong()) {
            errors += "seig sample-to-group mapping count does not match fragment samples"
            return SampleGroupParseResult(true, false, null)
        }
        val metadata = SampleGroupEncryptionMetadata(
            descriptionVersion = description?.version,
            defaultSampleDescriptionIndex = when {
                localDefaultIndex > 0 -> 0x10000L + localDefaultIndex
                else -> trackDefaultIndex
            },
            entries = combinedEntries,
            runs = resolvedRuns
        )
        return SampleGroupParseResult(true, true, metadata)
    }

    private fun parseSeigSgpd(p: ByteBuffer): ParsedSeigDescription? {
        require(p.remaining() >= 8) { "Truncated sgpd box" }
        val version = u8(p)
        require(version in 0..2) { "Unsupported sgpd version $version" }
        val flags = (u8(p) shl 16) or (u8(p) shl 8) or u8(p)
        require(flags == 0) { "Unsupported sgpd flags" }
        val groupingType = readFourCc(p)
        if (groupingType != "seig") return null
        val defaultLength = if (version >= 1) u32(p) else 0L
        val defaultIndex = if (version >= 2) u32(p) else 0L
        val entryCount = u32(p)
        require(entryCount <= MAX_FRAGMENT_SAMPLES.toLong()) { "sgpd entry count exceeds limit" }
        val entries = ArrayList<SeigProtectionEntry>(entryCount.toInt())
        repeat(entryCount.toInt()) { index ->
            val parsed = if (version >= 1 && defaultLength == 0L) {
                val descriptionLength = u32(p)
                require(descriptionLength in 20L..256L) { "Invalid seig description length" }
                val bytes = ByteArray(descriptionLength.toInt())
                p.get(bytes)
                val entryBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                val entry = parseSeigEntry(entryBuffer, index + 1)
                entry
            } else if (version >= 1 && defaultLength > 0L) {
                require(defaultLength in 20L..256L) { "Invalid default seig description length" }
                val bytes = ByteArray(defaultLength.toInt())
                p.get(bytes)
                val entryBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                parseSeigEntry(entryBuffer, index + 1)
            } else {
                parseSeigEntry(p, index + 1)
            }
            entries += parsed
        }
        require(!p.hasRemaining()) { "Unexpected trailing bytes in seig sgpd" }
        return ParsedSeigDescription(version, defaultIndex, entries)
    }

    private fun parseSeigEntry(p: ByteBuffer, index: Int): SeigProtectionEntry {
        require(p.remaining() >= 20) { "Truncated seig sample-group entry" }
        val reserved = u8(p)
        require(reserved == 0) { "Unsupported seig multi-key/reserved flags" }
        val pattern = u8(p)
        val cryptByteBlock = pattern ushr 4
        val skipByteBlock = pattern and 0x0f
        val protectedValue = u8(p)
        require(protectedValue in 0..1) { "Invalid seig isProtected value" }
        val perSampleIvSize = u8(p)
        require(perSampleIvSize in setOf(0, 8, 16)) { "Unsupported seig per-sample IV size" }
        skip(p, 16) // KID bytes are deliberately skipped and never retained.
        val isProtected = protectedValue == 1
        var constantIvSize: Int? = null
        if (isProtected && perSampleIvSize == 0) {
            val size = u8(p)
            require(size == 8 || size == 16) { "Unsupported seig constant IV size" }
            skip(p, size)
            constantIvSize = size
        }
        if (!isProtected) {
            require(perSampleIvSize == 0) { "Clear seig group must not specify a per-sample IV" }
        }
        return SeigProtectionEntry(
            entryIndex = index,
            isProtected = isProtected,
            perSampleIvSize = perSampleIvSize,
            cryptByteBlock = cryptByteBlock,
            skipByteBlock = skipByteBlock,
            constantIvSize = constantIvSize
        )
    }

    private fun parseSeigSbgp(p: ByteBuffer): ParsedSeigMapping? {
        require(p.remaining() >= 8) { "Truncated sbgp box" }
        val version = u8(p)
        require(version in 0..1) { "Unsupported sbgp version $version" }
        val flags = (u8(p) shl 16) or (u8(p) shl 8) or u8(p)
        require(flags == 0) { "Unsupported sbgp flags" }
        val groupingType = readFourCc(p)
        if (groupingType != "seig") return null
        if (version == 1) skip(p, 4) // grouping_type_parameter
        val entryCount = u32(p)
        require(entryCount <= MAX_FRAGMENT_SAMPLES.toLong()) { "sbgp entry count exceeds limit" }
        val runs = ArrayList<SampleGroupRun>(entryCount.toInt())
        repeat(entryCount.toInt()) {
            val sampleCount = u32(p)
            val groupDescriptionIndex = u32(p)
            require(sampleCount > 0) { "sbgp run has zero sample_count" }
            runs += SampleGroupRun(sampleCount, groupDescriptionIndex)
        }
        require(!p.hasRemaining()) { "Unexpected trailing bytes in seig sbgp" }
        return ParsedSeigMapping(runs)
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
        trackEncryption: TrackEncryptionMetadata?,
        forceIncomplete: Boolean = false
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

        // seig may override protection state and IV size per sample. Until the effective
        // sample-to-group mapping is applied to every senc record, do not advance into the
        // record bytes using tenc defaults. Retain only the declared sample count and flags.
        if (forceIncomplete) {
            return SencSummary(sampleCount, subsampleEncryption, false, emptyList(), overrideIvSize)
        }

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
        val parameterSetFingerprints = (
            vpsNals.map { parameterSetFingerprint("vps", it) } +
                spsNals.map { parameterSetFingerprint("sps", it) } +
                ppsNals.map { parameterSetFingerprint("pps", it) }
        ).sorted()
        return HevcConfiguration(
            version, profileSpace, tierFlag, profileIdc, compatibility, constraints, level,
            segmentationField and 0x0fff, parallelismField and 0x03, chromaField and 0x03,
            (lumaDepthField and 0x07) + 8, (chromaDepthField and 0x07) + 8,
            averageFrameRate, constantFrameRate, temporalLayers, temporalIdNested, nalLengthSize,
            vpsNals.size, spsNals.size, ppsNals.size, vps, sps, pps, parameterErrors,
            parameterSetFingerprints
        )
    }

    private fun parameterSetFingerprint(kind: String, nal: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(nal)
        val encoded = buildString(digest.size * 2) {
            digest.forEach { value -> append("%02x".format(value.toInt() and 0xff)) }
        }
        return "$kind:$encoded"
    }

    /**
     * Reads only the track-level encryption shape. KIDs, constant IV bytes and all actual
     * sample-encryption payloads are skipped and never returned or logged.
     */
    private fun parseTrackEncryption(p: ByteBuffer): TrackEncryptionMetadata {
        require(p.remaining() >= 4 + 1 + 2 + 16) { "Truncated tenc box" }
        val version = u8(p)
        val flags = (u8(p) shl 16) or (u8(p) shl 8) or u8(p)
        require(version in 0..1) { "Unsupported tenc version $version" }
        require(flags == 0) { "Unsupported tenc flags" }
        val reserved = u8(p)
        require(reserved == 0) { "Invalid tenc reserved byte" }
        val pattern = if (version == 1) u8(p) else 0
        val cryptBlocks = if (version == 1) pattern ushr 4 else 0
        val skipBlocks = if (version == 1) pattern and 0x0f else 0
        require(p.remaining() >= 2 + 16) { "Truncated tenc protection defaults" }
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
