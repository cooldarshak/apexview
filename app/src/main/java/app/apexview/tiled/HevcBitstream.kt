package app.apexview.tiled

/**
 * Minimal HEVC elementary-stream primitives for the tiled playback investigation.
 *
 * This parser deliberately does not parse encrypted samples or stitch streams. Callers must
 * provide clear Annex-B bytes. Protected CMAF samples require container-aware subsample handling
 * and DRM-safe processing before any bitstream inspection can be attempted.
 */
internal object HevcBitstream {

    data class NalUnit(
        val bytes: ByteArray,
        val type: Int,
        val layerId: Int,
        val temporalIdPlus1: Int,
    )

    /**
     * Borrowed, bounded view of a clear input NAL. Valid only during synchronous
     * composition; its owner must keep backing bytes unchanged until return.
     * The backing array is never exposed as an owned output.
     */
    internal class NalSpan(val backing: ByteArray, val offset: Int, val length: Int) {
        private val header = readNalHeader(backing, offset, length)
        val type: Int get() = header.type
        val layerId: Int get() = header.layerId
        val temporalIdPlus1: Int get() = header.temporalIdPlus1
        val size: Int get() = length
        operator fun get(index: Int): Byte {
            require(index in 0 until length) { "NAL span index out of bounds" }
            return backing[offset + index]
        }
        // Only small parameter sets use the standalone legacy parsers.
        fun parameterBytes(): ByteArray {
            require(type in 32..34) { "Only parameter sets can be materialized from a borrowed span" }
            return if (offset == 0 && length == backing.size) backing
                else backing.copyOfRange(offset, offset + length)
        }
    }

    internal fun NalUnit.asSpan(): NalSpan = NalSpan(bytes, 0, bytes.size)

    /** Header fields only: inspecting a multi-megabyte VCL NAL must not clone its payload. */
    data class NalHeader(val type: Int, val layerId: Int, val temporalIdPlus1: Int)

    /**
     * Splits an Annex-B byte stream into NAL units. Both 3-byte and 4-byte start codes are accepted.
     * Leading/trailing zero bytes are tolerated; empty NAL units and invalid HEVC headers fail.
     */
    fun splitAnnexB(stream: ByteArray): List<NalUnit> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < stream.size) {
            val prefix = when {
                i + 3 < stream.size &&
                    stream[i] == 0.toByte() && stream[i + 1] == 0.toByte() &&
                    stream[i + 2] == 0.toByte() && stream[i + 3] == 1.toByte() -> 4
                stream[i] == 0.toByte() && stream[i + 1] == 0.toByte() &&
                    stream[i + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (prefix != 0) {
                starts += i to prefix
                i += prefix
            } else {
                i++
            }
        }
        require(starts.isNotEmpty()) { "No Annex-B start code found" }

        return starts.mapIndexed { index, (start, prefix) ->
            val payloadStart = start + prefix
            val payloadEnd = if (index + 1 < starts.size) starts[index + 1].first else stream.size
            var end = payloadEnd
            while (end > payloadStart && stream[end - 1] == 0.toByte()) end--
            require(end - payloadStart >= 2) { "Truncated or empty HEVC NAL unit" }
            val bytes = stream.copyOfRange(payloadStart, end)
            // The split already owns these bytes. parseNalHeader() deliberately clones
            // a caller's NAL, which would duplicate every multi-megabyte slice again.
            val header = readNalHeader(bytes)
            NalUnit(bytes, header.type, header.layerId, header.temporalIdPlus1)
        }
    }

    fun readNalHeader(nal: ByteArray): NalHeader = readNalHeader(nal, 0, nal.size)

    /** Inspect a validated NAL range without cloning its full CABAC payload. */
    fun readNalHeader(nal: ByteArray, offset: Int, length: Int): NalHeader {
        require(offset >= 0 && length >= 2 && offset <= nal.size && length <= nal.size - offset) {
            "HEVC NAL header range is out of bounds"
        }
        val first = nal[offset].toInt() and 0xff
        val second = nal[offset + 1].toInt() and 0xff
        require((first and 0x80) == 0) { "forbidden_zero_bit must be zero" }
        val temporalIdPlus1 = second and 0x07
        require(temporalIdPlus1 != 0) { "nuh_temporal_id_plus1 must not be zero" }
        val type = (first ushr 1) and 0x3f
        val layerId = ((first and 0x01) shl 5) or (second ushr 3)
        return NalHeader(type, layerId, temporalIdPlus1)
    }

    /** Keep the original owned-byte contract for clients that need a standalone NAL. */
    fun parseNalHeader(nal: ByteArray): NalUnit {
        val header = readNalHeader(nal)
        return NalUnit(nal.copyOf(), header.type, header.layerId, header.temporalIdPlus1)
    }

    /**
     * Removes HEVC emulation-prevention bytes from an EBSP payload.
     * The input must not include the two-byte NAL header.
     */
    fun ebspToRbsp(ebsp: ByteArray): ByteArray = ebspToRbsp(ebsp, 0, ebsp.size)

    /** Read a validated range directly, without allocating an intermediate copyOfRange(). */
    fun ebspToRbsp(ebsp: ByteArray, offset: Int, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= ebsp.size && length <= ebsp.size - offset) {
            "HEVC EBSP range is out of bounds"
        }
        val out = ByteArray(length)
        var written = 0
        var zeroCount = 0
        var i = offset
        val end = offset + length
        while (i < end) {
            val value = ebsp[i].toInt() and 0xff
            if (zeroCount >= 2 && value == 0x03) {
                require(i + 1 < end) { "Emulation-prevention byte at end of payload" }
                val next = ebsp[i + 1].toInt() and 0xff
                require(next <= 0x03) { "Invalid emulation-prevention sequence" }
                zeroCount = 0
                i++
                continue
            }
            out[written++] = ebsp[i]
            zeroCount = if (value == 0) zeroCount + 1 else 0
            i++
        }
        return if (written == length) out else out.copyOf(written)
    }
}

/** Small MSB-first bit reader used by subsequent SPS/PPS/slice-header parsing. */
internal class HevcBitReader(private val data: ByteArray) {
    private var bitOffset = 0

    val bitsRemaining: Int get() = data.size * 8 - bitOffset
    val positionBits: Int get() = bitOffset

    fun readBit(): Int = readBits(1)

    fun readBits(count: Int): Int {
        require(count in 0..32) { "Bit count must be between 0 and 32" }
        require(count <= bitsRemaining) { "Unexpected end of HEVC bitstream" }
        var value = 0
        repeat(count) {
            val byte = data[bitOffset / 8].toInt() and 0xff
            val bit = (byte ushr (7 - bitOffset % 8)) and 1
            value = (value shl 1) or bit
            bitOffset++
        }
        return value
    }

    /** Reads an unsigned Exp-Golomb value, rejecting overflow and unterminated codes. */
    fun readUnsignedExpGolomb(): Long {
        var leadingZeroBits = 0
        while (true) {
            require(bitsRemaining > 0) { "Unterminated Exp-Golomb code" }
            if (readBit() == 1) break
            leadingZeroBits++
            require(leadingZeroBits <= 31) { "Exp-Golomb value exceeds supported range" }
        }
        if (leadingZeroBits == 0) return 0
        val suffix = readBits(leadingZeroBits).toLong()
        return ((1L shl leadingZeroBits) - 1L) + suffix
    }
}
