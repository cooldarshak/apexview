package app.apexview.tiled

/**
 * Maps ISO-BMFF CENC subsample clear/encrypted ranges onto length-prefixed HEVC NAL units.
 * Read-only: encrypted payload bytes are never decrypted or interpreted.
 */
internal object ProtectedHevcSampleLayout {
    data class ProtectionRange(
        /** Offset relative to the start of the NAL, excluding its length prefix. */
        val offset: Int,
        val lengthBytes: Int,
        val isClear: Boolean
    ) {
        val endExclusive: Int get() = Math.addExact(offset, lengthBytes)
    }

    data class NalUnit(
        val index: Int,
        val lengthFieldOffset: Int,
        val nalOffset: Int,
        val nalLength: Int,
        val nalType: Int,
        val layerId: Int,
        val temporalIdPlus1: Int,
        /** Contiguous clear bytes from the NAL start, excluding the length field. */
        val clearNalPrefixBytes: Int,
        /** Complete clear/encrypted byte map for this NAL, relative to the NAL start. */
        val protectionRanges: List<ProtectionRange> = emptyList()
    )

    data class Report(
        val sampleSizeBytes: Int,
        val nalLengthSize: Int,
        val nals: List<NalUnit>,
        val clearByteCount: Long,
        val encryptedByteCount: Long,
        /** Complete sample byte map. Offsets include NAL length prefixes. */
        val protectionRanges: List<ProtectionRange> = emptyList()
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
        require(subsampleRanges.size <= MAX_SUBSAMPLE_RANGES) {
            "$context: subsample map contains too many ranges"
        }

        val clearRanges = ArrayList<ClearRange>(subsampleRanges.size)
        val sampleProtectionRanges = ArrayList<ProtectionRange>(subsampleRanges.size * 2)
        var cursor = 0L
        var clearTotal = 0L
        var encryptedTotal = 0L

        fun appendSampleRange(start: Long, length: Long, isClear: Boolean) {
            if (length == 0L) return
            require(start >= 0 && length > 0 && start + length <= sample.size.toLong()) {
                "$context: protection range exceeds sample bounds"
            }
            val startInt = start.toInt()
            val lengthInt = length.toInt()
            val previous = sampleProtectionRanges.lastOrNull()
            // Clear spans may be coalesced. Never merge adjacent encrypted spans: separate
            // CENC subsample entries are separate CBCS chains, even when clearBytes is zero.
            if (isClear && previous != null && previous.isClear &&
                previous.endExclusive == startInt
            ) {
                sampleProtectionRanges[sampleProtectionRanges.lastIndex] = previous.copy(
                    lengthBytes = Math.addExact(previous.lengthBytes, lengthInt)
                )
            } else {
                sampleProtectionRanges += ProtectionRange(startInt, lengthInt, isClear)
            }
        }

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
                appendSampleRange(cursor, range.clearBytes.toLong(), isClear = true)
            }
            cursor = clearEnd
            clearTotal = Math.addExact(clearTotal, range.clearBytes.toLong())
            cursor = Math.addExact(cursor, range.encryptedBytes)
            require(cursor <= sample.size.toLong()) {
                "$context: subsample encrypted range $index exceeds sample bounds"
            }
            appendSampleRange(clearEnd, range.encryptedBytes, isClear = false)
            encryptedTotal = Math.addExact(encryptedTotal, range.encryptedBytes)
        }
        require(cursor == sample.size.toLong()) {
            "$context: subsample map covers " + cursor + " bytes but sample contains " + sample.size
        }
        require(sampleProtectionRanges.sumOf { it.lengthBytes.toLong() } == sample.size.toLong()) {
            "$context: normalised protection map does not cover the complete sample"
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

        fun rangesForNal(nalOffset: Int, nalLength: Int): List<ProtectionRange> {
            val nalEnd = Math.addExact(nalOffset, nalLength)
            val result = ArrayList<ProtectionRange>()
            var coveredUntil = nalOffset
            sampleProtectionRanges.forEach { range ->
                val rangeStart = range.offset
                val rangeEnd = range.endExclusive
                val intersectionStart = maxOf(nalOffset, rangeStart)
                val intersectionEnd = minOf(nalEnd, rangeEnd)
                if (intersectionStart < intersectionEnd) {
                    require(intersectionStart == coveredUntil) {
                        "$context: protection map has a gap inside a NAL unit"
                    }
                    result += ProtectionRange(
                        offset = intersectionStart - nalOffset,
                        lengthBytes = intersectionEnd - intersectionStart,
                        isClear = range.isClear
                    )
                    coveredUntil = intersectionEnd
                }
            }
            require(coveredUntil == nalEnd) {
                "$context: protection map does not cover the complete NAL unit"
            }
            return result
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
            val header = HevcBitstream.readNalHeader(sample, nalOffset, 2)
            val nalLengthInt = nalLength.toInt()
            nals += NalUnit(
                index = nals.size,
                lengthFieldOffset = offset,
                nalOffset = nalOffset,
                nalLength = nalLengthInt,
                nalType = header.type,
                layerId = header.layerId,
                temporalIdPlus1 = header.temporalIdPlus1,
                clearNalPrefixBytes = minOf(nalLengthInt, contiguousClearBytes(nalOffset)),
                protectionRanges = rangesForNal(nalOffset, nalLengthInt)
            )
            require(nals.size <= MAX_NAL_UNITS) {
                "$context: sample contains too many NAL units"
            }
            offset = Math.addExact(nalOffset, nalLengthInt)
        }
        require(offset == sample.size && nals.isNotEmpty()) {
            "$context: sample does not contain complete length-prefixed NAL units"
        }
        return Report(
            sampleSizeBytes = sample.size,
            nalLengthSize = nalLengthSize,
            nals = nals,
            clearByteCount = clearTotal,
            encryptedByteCount = encryptedTotal,
            protectionRanges = sampleProtectionRanges
        )
    }

    private const val MAX_SAMPLE_BYTES = 64 * 1024 * 1024
    private const val MAX_NAL_UNITS = 4096
    private const val MAX_SUBSAMPLE_RANGES = 1_000_000
}
