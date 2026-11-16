package app.apexview.tiled

import java.io.ByteArrayOutputStream

/**
 * Builds one length-prefixed HEVC CMAF sample from rewritten protected slice NALs.
 *
 * Each input NAL's protection ranges are carried into the output sample without merging adjacent
 * encrypted spans. That boundary is significant for CBCS because each protected subsample starts
 * its own cipher-block chain. NAL length prefixes are always emitted as clear bytes.
 *
 * This creates sample bytes and the senc-compatible subsample range list only. It does not create
 * senc/saiz/saio boxes, select a sample IV, compare source key identities, or build an init segment.
 */
internal object ProtectedHevcSampleComposer {
    data class Result(
        val sampleBytes: ByteArray,
        val nalLengthSize: Int,
        val nalCount: Int,
        val subsampleRanges: List<CmafBoxInspector.SubsampleEncryptionRange>,
        val encryptedByteCount: Long
    )

    fun compose(
        slices: List<ProtectedHevcSliceHeaderRewriter.Result>,
        nalLengthSize: Int,
        context: String = "Protected HEVC sample composition"
    ): Result {
        require(slices.isNotEmpty()) { "$context: no protected slice NALs were supplied" }
        require(slices.size <= MAX_NAL_UNITS) { "$context: too many slice NALs" }
        require(nalLengthSize in 1..4) { "$context: unsupported NAL length size" }

        val maximumNalLength = if (nalLengthSize == 4) {
            0xffff_ffffL
        } else {
            (1L shl (nalLengthSize * 8)) - 1L
        }
        var totalSampleBytes = 0L
        slices.forEachIndexed { index, slice ->
            val nal = slice.nal
            require(nal.size >= 2 && nal.size.toLong() <= maximumNalLength) {
                "$context: NAL $index does not fit the selected length-prefix width"
            }
            require(nal.size <= MAX_SAMPLE_BYTES) {
                "$context: NAL $index exceeds the sample inspection limit"
            }
            validateNalProtectionMap(slice, index, context)
            totalSampleBytes = Math.addExact(totalSampleBytes, nalLengthSize.toLong() + nal.size)
            require(totalSampleBytes <= MAX_SAMPLE_BYTES.toLong()) {
                "$context: composed sample exceeds the inspection limit"
            }
        }

        val output = ByteArrayOutputStream(totalSampleBytes.toInt())
        val subsampleRanges = ArrayList<CmafBoxInspector.SubsampleEncryptionRange>()
        var pendingClearBytes = 0L
        var encryptedTotal = 0L

        fun appendSubsample(clearBytes: Long, encryptedBytes: Long) {
            require(clearBytes in 0L..MAX_CLEAR_BYTES) {
                "$context: clear-byte count exceeds the CENC 16-bit field"
            }
            require(encryptedBytes in 0L..MAX_ENCRYPTED_BYTES) {
                "$context: encrypted-byte count exceeds the CENC 32-bit field"
            }
            require(subsampleRanges.size < MAX_SUBSAMPLE_RANGES) {
                "$context: generated subsample map contains too many entries"
            }
            subsampleRanges += CmafBoxInspector.SubsampleEncryptionRange(
                clearBytes = clearBytes.toInt(),
                encryptedBytes = encryptedBytes
            )
        }

        fun flushEncryptedRange(encryptedBytes: Long) {
            require(encryptedBytes in 1L..MAX_ENCRYPTED_BYTES) {
                "$context: encrypted range has an unsupported length"
            }
            // clear_bytes is uint16. Represent long clear runs as clear-only subsample entries
            // rather than changing the following protected range's byte position.
            while (pendingClearBytes > MAX_CLEAR_BYTES) {
                appendSubsample(MAX_CLEAR_BYTES, 0L)
                pendingClearBytes -= MAX_CLEAR_BYTES
            }
            appendSubsample(pendingClearBytes, encryptedBytes)
            pendingClearBytes = 0L
            encryptedTotal = Math.addExact(encryptedTotal, encryptedBytes)
        }

        slices.forEachIndexed { index, slice ->
            val nal = slice.nal
            for (byteIndex in nalLengthSize - 1 downTo 0) {
                output.write((nal.size ushr (byteIndex * 8)) and 0xff)
            }
            // The length prefix is part of the clear run preceding this NAL's first encrypted
            // span, and is therefore recorded in the output CENC subsample map.
            pendingClearBytes = Math.addExact(pendingClearBytes, nalLengthSize.toLong())
            slice.protectionRanges.forEach { range ->
                if (range.isClear) {
                    pendingClearBytes = Math.addExact(pendingClearBytes, range.lengthBytes.toLong())
                } else {
                    flushEncryptedRange(range.lengthBytes.toLong())
                }
            }
            output.write(nal)
        }

        while (pendingClearBytes > MAX_CLEAR_BYTES) {
            appendSubsample(MAX_CLEAR_BYTES, 0L)
            pendingClearBytes -= MAX_CLEAR_BYTES
        }
        if (pendingClearBytes > 0L) appendSubsample(pendingClearBytes, 0L)

        val sampleBytes = output.toByteArray()
        require(sampleBytes.size.toLong() == totalSampleBytes) {
            "$context: output sample byte count differs from its calculated size"
        }
        require(subsampleRanges.isNotEmpty()) {
            "$context: output sample has no CENC subsample map"
        }
        val mappedSampleBytes = subsampleRanges.sumOf {
            Math.addExact(it.clearBytes.toLong(), it.encryptedBytes)
        }
        require(mappedSampleBytes == sampleBytes.size.toLong()) {
            "$context: generated CENC subsample map does not cover the output sample"
        }
        val mappedEncryptedBytes = subsampleRanges.sumOf { it.encryptedBytes }
        require(mappedEncryptedBytes == encryptedTotal) {
            "$context: generated CENC subsample map changed the encrypted byte count"
        }

        return Result(
            sampleBytes = sampleBytes,
            nalLengthSize = nalLengthSize,
            nalCount = slices.size,
            subsampleRanges = subsampleRanges,
            encryptedByteCount = encryptedTotal
        )
    }

    private fun validateNalProtectionMap(
        slice: ProtectedHevcSliceHeaderRewriter.Result,
        nalIndex: Int,
        context: String
    ) {
        val nal = slice.nal
        val ranges = slice.protectionRanges
        require(ranges.isNotEmpty()) {
            "$context: NAL $nalIndex has no clear/encrypted protection map"
        }
        var coveredUntil = 0
        var encryptedBytes = 0L
        ranges.forEachIndexed { rangeIndex, range ->
            require(range.offset == coveredUntil && range.lengthBytes > 0) {
                "$context: NAL $nalIndex protection range $rangeIndex has a gap, overlap or empty span"
            }
            require(range.endExclusive <= nal.size) {
                "$context: NAL $nalIndex protection range $rangeIndex exceeds its byte bounds"
            }
            coveredUntil = range.endExclusive
            if (!range.isClear) {
                encryptedBytes = Math.addExact(encryptedBytes, range.lengthBytes.toLong())
            }
        }
        require(coveredUntil == nal.size) {
            "$context: NAL $nalIndex protection map does not cover the complete NAL"
        }
        require(ranges.first().offset == 0 && ranges.first().isClear &&
            ranges.first().lengthBytes >= 2) {
            "$context: NAL $nalIndex does not expose a fully clear HEVC NAL header"
        }
        require(encryptedBytes == slice.encryptedByteCount) {
            "$context: NAL $nalIndex encrypted-byte count differs from its protection map"
        }
        val parsedHeader = HevcBitstream.parseNalHeader(nal.copyOfRange(0, 2))
        require(parsedHeader.type in 0..31) {
            "$context: NAL $nalIndex is not a VCL slice NAL"
        }
    }

    private const val MAX_SAMPLE_BYTES = 64 * 1024 * 1024
    private const val MAX_NAL_UNITS = 4096
    private const val MAX_SUBSAMPLE_RANGES = 1_000_000
    private const val MAX_CLEAR_BYTES = 0xffffL
    private const val MAX_ENCRYPTED_BYTES = 0xffff_ffffL
}
