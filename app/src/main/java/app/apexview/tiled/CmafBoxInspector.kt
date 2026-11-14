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
    data class TrackMetadata(
        val handler: String?,
        val timescale: Long?,
        val sampleEntry: String?,
        val originalFormat: String?,
        val protectionScheme: String?,
        val encrypted: Boolean,
        val width: Int?,
        val height: Int?
    )
    data class Report(
        val majorBrand: String?,
        val compatibleBrands: List<String>,
        val topLevelBoxes: List<String>,
        val tracks: List<TrackMetadata>,
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
        val tracks = boxes.firstOrNull { it.type == "moov" }?.children
            ?.filter { it.type == "trak" }?.map { track(data, it) }.orEmpty()
        return Report(major, brands, boxes.map { it.type }, tracks,
            boxes.any { it.type == "moof" } || boxes.any { it.type == "moov" && it.children.any { c -> c.type == "mvex" } },
            errors)
    }

    private fun track(data: ByteArray, trak: Box): TrackMetadata {
        val all = flatten(trak).toMutableList()
        val stsdBox = all.firstOrNull { it.type == "stsd" }
        if (stsdBox != null) {
            val p = payload(data, stsdBox)
            if (p.remaining() >= 16) {
                val entryStart = stsdBox.start + stsdBox.headerSize + 8
                val entrySize = uint32(data, entryStart)
                val entryType = String(data, entryStart + 4, 4, Charsets.ISO_8859_1)
                val fixedHeader = if (entryType == "encv" || handlerFor(trak, data) == "vide") 78 else 28
                val childStart = entryStart + 8 + fixedHeader
                val entryEnd = entryStart + entrySize.toInt()
                if (entrySize >= 8L + fixedHeader && entryEnd <= stsdBox.start + stsdBox.size && childStart <= entryEnd) {
                    val entryChildren = parseBoxes(data, childStart, entryEnd, 1, intArrayOf(0), mutableListOf())
                    all += entryChildren.flatMap { flatten(it) }
                }
            }
        }
        val mdhd = all.firstOrNull { it.type == "mdhd" }?.let { payload(data, it) }
        val timescale = mdhd?.let { readTimescale(it) }
        val handler = all.firstOrNull { it.type == "hdlr" }?.let { payload(data, it) }?.let { p ->
            if (p.remaining() >= 12) { p.position(p.position() + 8); readFourCc(p) } else null
        }
        val stsd = all.firstOrNull { it.type == "stsd" }?.let { payload(data, it) }
        var entry: String? = null
        var width: Int? = null
        var height: Int? = null
        if (stsd != null && stsd.remaining() >= 16) {
            stsd.position(stsd.position() + 8) // full box header + entry count
            entry = readFourCc(stsd)
            if (stsd.remaining() >= 28 && (handler == "vide")) {
                stsd.position(stsd.position() + 16)
                width = stsd.short.toInt() and 0xffff
                height = stsd.short.toInt() and 0xffff
            }
        }
        val original = all.firstOrNull { it.type == "frma" }?.let { payload(data, it) }?.takeIf { it.remaining() >= 4 }?.let { readFourCc(it) }
        val scheme = all.firstOrNull { it.type == "schm" }?.let { payload(data, it) }?.takeIf { it.remaining() >= 8 }?.let { p ->
            p.position(p.position() + 4); readFourCc(p)
        }
        return TrackMetadata(handler, timescale, entry, original, scheme,
            entry == "encv" || entry == "enca" || scheme != null, width, height)
    }

    private fun handlerFor(trak: Box, data: ByteArray): String? =
        flatten(trak).firstOrNull { it.type == "hdlr" }?.let { payload(data, it) }?.let { p ->
            if (p.remaining() >= 12) { p.position(p.position() + 8); readFourCc(p) } else null
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
