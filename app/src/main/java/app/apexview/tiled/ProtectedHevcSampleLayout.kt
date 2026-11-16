package app.apexview.tiled

/**
 * Maps ISO-BMFF CENC subsample clear/encrypted ranges onto length-prefixed HEVC NAL units.
 * Read-only: encrypted payload bytes are never decrypted, copied, or interpreted.
 */
internal object ProtectedHevcSampleLayout {
    data class NalUnit(
        val index: Int,
        val lengthFieldOffset: Int,
        val nalOffset: Int,
        val nalLength: Int,
        val nalType: Int,
        val layerId: Int,
        val temporalIdPlus1: Int,
        /** Contiguous clear bytes from the NAL start, excluding the length field. */
        val clearNalPrefixBytes: Int
    )

    data class Report(
        val sampleSizeBytes: Int,
        val nalLengthSize: Int,
        val nals: List<NalUnit>,
        val clearByteCount: Long,
        val encryptedByteCount: Long
    )

    private data class ClearRange(val start: Int, val endExclusive: Int)

    fun inspect(
        sample: ByteArray,
        nalLengthSize: Int,
        subsampleRanges: List<CmafBoxInspector.SubsampleEncryptionRange>,
        context: String = "Protected HEVC sample"
    ): Report {
        require(sample.isNotEmpty()) { "$context: sample is empty" }
        require(sample.size <= MAX_SAMPLE_BYTES) { "$context: sample exceeds inspection limit" }
        require(nalLengthSize in 1..4) { "$context: unsupported NAL length size" }
        require(subsampleRanges.isNotEmpty()) { "$context: subsample encryption map is empty" }

        val clearRanges = ArrayList<ClearRange>(subsampleRanges.size)
        var cursor = 0L
        var clearTotal = 0L
        var encryptedTotal = 0L
        subsampleRanges.forEachIndexed { index, range ->
            require(range.clearBytes >= 0 && range.encryptedBytes >= 0L) {
                "$context: subsample range $index contains a negative byte count"
            }
            val clearEnd = Math.addExact(cursor, range.clearBytes.toLong())
            require(clearEnd <= sample.size.toLong()) {
                "$context: subsample clear range $index exceeds sample bounds"
            }
            if (range.clearBytes > 0) {
                val start = cursor.toInt()
                val end = clearEnd.toInt()
                val previous = clearRanges.lastOrNull()
                if (previous != null && previous.endExclusive == start) {
                    clearRanges[clearRanges.lastIndex] = previous.copy(endExclusive = end)
                } else {
                    clearRanges += ClearRange(start, end)
                }
            }
            cursor = clearEnd
            clearTotal = Math.addExact(clearTotal, range.clearBytes.toLong())
            cursor = Math.addExact(cursor, range.encryptedBytes)
            require(cursor <= sample.size.toLong()) {
                "$context: subsample encrypted range $index exceeds sample bounds"
            }
            encryptedTotal = Math.addExact(encryptedTotal, range.encryptedBytes)
        }
        require(cursor == sample.size.toLong()) {
            "$context: subsample map covers " + cursor + " bytes but sample contains " + sample.size
        }

        fun requireClear(start: Int, length: Int, description: String) {
            val end = Math.addExact(start, length)
            val range = clearRanges.firstOrNull { start >= it.start && end <= it.endExclusive }
            require(range != null) { "$context: $description is not fully clear in the subsample map" }
        }

        fun contiguousClearBytes(start: Int): Int {
            val range = clearRanges.firstOrNull { start >= it.start && start < it.endExclusive }
                ?: return 0
            return range.endExclusive - start
        }

        val nals = ArrayList<NalUnit>()
        var offset = 0
        while (offset < sample.size) {
            require(sample.size - offset >= nalLengthSize) {
                "$context: truncated NAL length prefix at byte $offset"
            }
            requireClear(offset, nalLengthSize, "NAL length prefix at byte $offset")
            var nalLength = 0L
            repeat(nalLengthSize) { index ->
                nalLength = (nalLength shl 8) or (sample[offset + index].toLong() and 0xffL)
            }
            val nalOffset = Math.addExact(offset, nalLengthSize)
            require(nalLength >= 2L && nalLength <= (sample.size - nalOffset).toLong()) {
                "$context: invalid NAL length at byte $offset"
            }
            requireClear(nalOffset, 2, "HEVC NAL header at byte $nalOffset")
            val header = HevcBitstream.parseNalHeader(sample.copyOfRange(nalOffset, nalOffset + 2))
            nals += NalUnit(
                index = nals.size,
                lengthFieldOffset = offset,
                nalOffset = nalOffset,
                nalLength = nalLength.toInt(),
                nalType = header.type,
                layerId = header.layerId,
                temporalIdPlus1 = header.temporalIdPlus1,
                clearNalPrefixBytes = minOf(nalLength.toInt(), contiguousClearBytes(nalOffset))
            )
            require(nals.size <= MAX_NAL_UNITS) {
                "$context: sample contains too many NAL units"
            }
            offset = Math.addExact(nalOffset, nalLength.toInt())
        }
        require(offset == sample.size && nals.isNotEmpty()) {
            "$context: sample does not contain complete length-prefixed NAL units"
        }
        return Report(sample.size, nalLengthSize, nals, clearTotal, encryptedTotal)
    }

    private const val MAX_SAMPLE_BYTES = 64 * 1024 * 1024
    private const val MAX_NAL_UNITS = 4096
}
