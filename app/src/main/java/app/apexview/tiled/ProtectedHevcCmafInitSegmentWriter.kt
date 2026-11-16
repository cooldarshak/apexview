package app.apexview.tiled

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites only the HEVC decoder configuration and dimensions in a protected source init.
 * Protection boxes and all non-video metadata are retained byte-for-byte.
 */
internal object ProtectedHevcCmafInitSegmentWriter {
    private data class Box(val type: String, val start: Int, val size: Long, val header: Int) {
        val payloadStart get() = start + header
        val end get() = Math.addExact(start.toLong(), size).toInt()
    }

    fun rewrite(
        source: ByteArray,
        output: HevcOutputParameterSetWriter.Output,
        context: String = "Protected HEVC init rewrite"
    ): ByteArray {
        require(source.isNotEmpty() && source.size <= MAX_INIT_BYTES) {
            "$context: source init is empty or exceeds its size bound"
        }
        val before = CmafBoxInspector.inspect(source)
        require(before.errors.isEmpty() && before.initializationSegmentPresent) {
            "$context: source initialization metadata is incomplete: " +
                before.errors.joinToString("; ")
        }
        val videoTracks = before.tracks.filter { it.handler == "vide" }
        require(videoTracks.size == 1) { "$context: exactly one video track is required" }
        val sourceTrack = videoTracks.single()
        require(sourceTrack.encrypted && sourceTrack.sampleEntry == "encv" &&
            sourceTrack.originalFormat in setOf("hvc1", "hev1") &&
            sourceTrack.protectionScheme == "cbcs" &&
            sourceTrack.trackEncryption?.defaultIsProtected == true) {
            "$context: source is not a protected CBCS HEVC video track"
        }
        require(!sourceTrack.sampleGroupDescriptionPresent && !sourceTrack.sampleToGroupPresent &&
            sourceTrack.seigSampleGroupDescription == null) {
            "$context: track-level sample-group overrides are not supported"
        }

        val outputVps = HevcParameterSets.parseVps(output.vps)
        val outputSps = HevcParameterSets.parseSps(output.sps)
        val outputPps = HevcParameterSets.parsePps(output.pps, outputSps)
        require(outputSps.vpsId == outputVps.vpsId && outputPps.spsId == outputSps.spsId &&
            outputSps.width == output.codedWidth && outputSps.height == output.codedHeight) {
            "$context: output VPS/SPS/PPS or coded dimensions are inconsistent"
        }
        require(outputSps.displayWidth in 1..0xffff && outputSps.displayHeight in 1..0xffff) {
            "$context: output display dimensions exceed visual sample-entry limits"
        }
        val replacement = HevcClearCmafInitSegmentWriter.writeHevcConfiguration(output, outputSps)

        val top = parseBoxes(source, 0, source.size)
        val moov = top.singleOrNull { it.type == "moov" }
            ?: throw IllegalArgumentException("$context: source must contain one moov")
        val traks = children(source, moov).filter { it.type == "trak" }
        val videoTraks = traks.filter { trak ->
            val mdia = children(source, trak).singleOrNull { it.type == "mdia" } ?: return@filter false
            val hdlr = children(source, mdia).singleOrNull { it.type == "hdlr" }
                ?: return@filter false
            handlerType(source, hdlr) == "vide"
        }
        val trak = videoTraks.singleOrNull()
            ?: throw IllegalArgumentException("$context: could not locate the unique video trak")
        val mdia = children(source, trak).single { it.type == "mdia" }
        val minf = children(source, mdia).single { it.type == "minf" }
        val stbl = children(source, minf).single { it.type == "stbl" }
        val stsd = children(source, stbl).single { it.type == "stsd" }
        val stsdPayload = payload(source, stsd)
        require(stsdPayload.remaining() >= 8) { "$context: truncated stsd" }
        require(u8(stsdPayload) == 0 && readFlags(stsdPayload) == 0) {
            "$context: unsupported stsd version or flags"
        }
        require(u32(stsdPayload) == 1L) { "$context: exactly one sample description is supported" }
        val entries = parseBoxes(source, stsd.payloadStart + 8, stsd.end)
        val entry = entries.singleOrNull()?.takeIf { it.type == "encv" }
            ?: throw IllegalArgumentException("$context: active description is not one encv sample entry")
        require(entry.header == 8 && entry.size >= 86L) {
            "$context: unsupported protected visual sample-entry header"
        }
        val entryChildren = parseBoxes(source, entry.payloadStart + 78, entry.end)
        val hvcC = entryChildren.singleOrNull { it.type == "hvcC" }
            ?: throw IllegalArgumentException("$context: protected sample entry must have one hvcC")
        val tkhd = children(source, trak).single { it.type == "tkhd" }

        val delta = replacement.size.toLong() - hvcC.size
        val ancestors = listOf(entry, stsd, stbl, minf, mdia, trak, moov)
        ancestors.forEach { ancestor ->
            val updated = Math.addExact(ancestor.size, delta)
            require(updated >= ancestor.header.toLong() && updated <= MAX_BOX_SIZE) {
                "$context: an ancestor box exceeds supported ISO-BMFF size limits"
            }
        }
        val length = Math.addExact(source.size.toLong(), delta)
        require(length in 1..MAX_INIT_BYTES.toLong()) { "$context: rewritten init exceeds size limit" }
        val result = ByteArray(length.toInt())
        source.copyOfRange(0, hvcC.start).copyInto(result, 0)
        replacement.copyInto(result, hvcC.start)
        source.copyOfRange(hvcC.end, source.size).copyInto(result, hvcC.start + replacement.size)

        ancestors.forEach { ancestor ->
            writeBoxSize(result, ancestor.start, ancestor.header, ancestor.size + delta)
        }
        writeU16(result, entry.start + entry.header + 24, outputSps.displayWidth)
        writeU16(result, entry.start + entry.header + 26, outputSps.displayHeight)
        writeU32(result, tkhd.end - 8, outputSps.displayWidth.toLong() shl 16)
        writeU32(result, tkhd.end - 4, outputSps.displayHeight.toLong() shl 16)

        val after = CmafBoxInspector.inspect(result)
        require(after.errors.isEmpty() && after.initializationSegmentPresent) {
            "$context: rewritten protected init failed independent CMAF inspection: " +
                after.errors.joinToString("; ")
        }
        val outputTrack = after.tracks.singleOrNull { it.handler == "vide" }
            ?: throw IllegalArgumentException("$context: rewritten init lost its unique video track")
        require(outputTrack.trackId == sourceTrack.trackId &&
            outputTrack.timescale == sourceTrack.timescale &&
            outputTrack.sampleEntry == "encv" &&
            outputTrack.originalFormat == sourceTrack.originalFormat &&
            outputTrack.encrypted && outputTrack.protectionScheme == "cbcs" &&
            outputTrack.trackEncryption == sourceTrack.trackEncryption) {
            "$context: rewriting changed or lost source Widevine/CBCS track defaults"
        }
        val parsedOutput = requireNotNull(outputTrack.hevcConfiguration)
        require(parsedOutput.parameterSetErrors.isEmpty() &&
            parsedOutput.vps.singleOrNull() == outputVps &&
            parsedOutput.sps.singleOrNull() == outputSps &&
            parsedOutput.pps.singleOrNull() == outputPps) {
            "$context: output hvcC does not contain the requested grid parameter sets"
        }
        require(rawBoxes(source, "sinf") == rawBoxes(result, "sinf") &&
            rawBoxes(source, "tenc") == rawBoxes(result, "tenc") &&
            rawBoxes(source, "pssh") == rawBoxes(result, "pssh")) {
            "$context: sinf, tenc or pssh signalling changed during protected init rewrite"
        }
        return result
    }

    private fun handlerType(data: ByteArray, box: Box): String {
        val p = payload(data, box)
        require(p.remaining() >= 12) { "Truncated hdlr" }
        p.position(p.position() + 8)
        val bytes = ByteArray(4)
        p.get(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun rawBoxes(data: ByteArray, sought: String): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        fun walk(parent: Box?) {
            val siblings = if (parent == null) parseBoxes(data, 0, data.size) else {
                val childStart = when (parent.type) {
                    "stsd" -> parent.payloadStart + 8
                    "meta" -> parent.payloadStart + 4
                    "encv", "avc1", "hvc1", "hev1" -> parent.payloadStart + 78
                    else -> parent.payloadStart
                }
                if (childStart > parent.end) return
                parseBoxes(data, childStart, parent.end)
            }
            siblings.forEach { child ->
                if (child.type == sought) result += data.copyOfRange(child.start, child.end)
                if (child.type in CONTAINERS) walk(child)
            }
        }
        walk(null)
        return result
    }

    private fun parseBoxes(data: ByteArray, start: Int, end: Int): List<Box> {
        require(start >= 0 && end >= start && end <= data.size) { "Invalid ISO-BMFF parent bounds" }
        val result = mutableListOf<Box>()
        var offset = start
        while (offset < end) {
            require(end - offset >= 8 && result.size < MAX_BOX_COUNT) {
                "Truncated or excessive ISO-BMFF box sequence"
            }
            val size32 = readU32(data, offset)
            val type = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            val size = when (size32) {
                0L -> (end - offset).toLong()
                1L -> {
                    require(end - offset >= 16) { "Truncated extended box header" }
                    header = 16
                    ByteBuffer.wrap(data, offset + 8, 8).order(ByteOrder.BIG_ENDIAN).long
                        .also { require(it >= 0L) { "Extended box size exceeds signed range" } }
                }
                else -> size32
            }
            require(size >= header.toLong() && size <= (end - offset).toLong()) {
                "Invalid ISO-BMFF box size for " + type
            }
            result += Box(type, offset, size, header)
            offset = Math.addExact(offset, size.toInt())
        }
        require(offset == end) { "ISO-BMFF boxes do not cover their parent exactly" }
        return result
    }

    private fun children(data: ByteArray, parent: Box) =
        parseBoxes(data, parent.payloadStart, parent.end)

    private fun payload(data: ByteArray, box: Box) =
        ByteBuffer.wrap(data, box.payloadStart, box.size.toInt() - box.header)
            .slice().order(ByteOrder.BIG_ENDIAN)

    private fun readFlags(p: ByteBuffer) =
        (u8(p) shl 16) or (u8(p) shl 8) or u8(p)

    private fun u8(p: ByteBuffer) = p.get().toInt() and 0xff

    private fun u32(p: ByteBuffer) = p.int.toLong() and UINT32_MAX

    private fun readU32(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset <= data.size - 4)
        return ((data[offset].toLong() and 0xffL) shl 24) or
            ((data[offset + 1].toLong() and 0xffL) shl 16) or
            ((data[offset + 2].toLong() and 0xffL) shl 8) or
            (data[offset + 3].toLong() and 0xffL)
    }

    private fun writeU32(data: ByteArray, offset: Int, value: Long) {
        require(value in 0..UINT32_MAX && offset >= 0 && offset <= data.size - 4)
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt())
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        require(value in 0..0xffff && offset >= 0 && offset <= data.size - 2)
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort())
    }

    private fun writeBoxSize(data: ByteArray, offset: Int, header: Int, value: Long) {
        if (header == 8) {
            require(value <= UINT32_MAX) { "Updated box requires an extended size" }
            writeU32(data, offset, value)
        } else {
            require(header == 16 && value > UINT32_MAX) {
                "Extended box size cannot be represented after the patch"
            }
            ByteBuffer.wrap(data, offset + 8, 8).order(ByteOrder.BIG_ENDIAN).putLong(value)
        }
    }

    private val CONTAINERS = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex",
        "moof", "traf", "sinf", "schi", "udta", "meta", "stsd", "encv",
        "avc1", "hvc1", "hev1"
    )
    private const val UINT32_MAX = 0xffff_ffffL
    private const val MAX_BOX_SIZE = Int.MAX_VALUE.toLong()
    private const val MAX_INIT_BYTES = 8 * 1024 * 1024
    private const val MAX_BOX_COUNT = 20_000
}
