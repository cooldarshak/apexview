package app.apexview.tiled

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a single-track, clear CMAF media fragment.
 *
 * This writer deliberately does not accept encryption auxiliary data and never writes senc,
 * saiz, saio, seig, or protected sample bytes. The caller must establish that source samples are
 * clear before calling. A separately generated, matching HEVC initialization segment is required.
 */
internal object HevcClearCmafFragmentWriter {
    data class Sample(
        val data: ByteArray,
        val durationTicks: Long,
        val compositionTimeOffsetTicks: Long,
        val sampleFlags: Long
    )

    /** Rewritten HEVC NALs kept separate until the final CMAF buffer is allocated. */
    data class NalSample(
        val nals: List<ByteArray>,
        val durationTicks: Long,
        val compositionTimeOffsetTicks: Long,
        val sampleFlags: Long
    )

    private class EncodedSample(
        val size: Int,
        val durationTicks: Long,
        val compositionTimeOffsetTicks: Long,
        val sampleFlags: Long,
        val writePayload: (ByteBuffer) -> Unit
    )

    fun write(
        trackId: Long,
        sequenceNumber: Long,
        baseDecodeTimeTicks: Long,
        samples: List<Sample>,
        sourceTrackEncrypted: Boolean,
        sourceFragmentHasEncryptionMetadata: Boolean
    ): ByteArray = writePrepared(
        trackId, sequenceNumber, baseDecodeTimeTicks,
        samples.map { sample -> EncodedSample(sample.data.size, sample.durationTicks,
            sample.compositionTimeOffsetTicks, sample.sampleFlags) { output -> output.put(sample.data) } },
        sourceTrackEncrypted, sourceFragmentHasEncryptionMetadata
    )

    /** Directly prefix validated HEVC NALs into mdat without full-size per-picture copies. */
    fun writeNalSamples(
        trackId: Long,
        sequenceNumber: Long,
        baseDecodeTimeTicks: Long,
        samples: List<NalSample>,
        nalLengthSize: Int,
        sourceTrackEncrypted: Boolean,
        sourceFragmentHasEncryptionMetadata: Boolean
    ): ByteArray {
        require(nalLengthSize in 1..4) { "NAL length prefix size must be between 1 and 4" }
        val maxNalSize = if (nalLengthSize == 4) 0xffff_ffffL else (1L shl (8 * nalLengthSize)) - 1L
        val encoded = samples.map { sample ->
            require(sample.nals.isNotEmpty()) { "An output sample must contain a rewritten HEVC NAL" }
            val size = sample.nals.fold(0L) { bytes, nal ->
                require(nal.isNotEmpty() && nal.size.toLong() <= maxNalSize) {
                    "HEVC NAL exceeds the configured length prefix"
                }
                Math.addExact(bytes, nalLengthSize.toLong() + nal.size)
            }
            require(size <= Int.MAX_VALUE) { "CMAF sample exceeds array capacity" }
            EncodedSample(size.toInt(), sample.durationTicks, sample.compositionTimeOffsetTicks,
                sample.sampleFlags) { output ->
                sample.nals.forEach { nal ->
                    for (shift in (nalLengthSize - 1) downTo 0) {
                        output.put(((nal.size ushr (shift * 8)) and 0xff).toByte())
                    }
                    output.put(nal)
                }
            }
        }
        return writePrepared(trackId, sequenceNumber, baseDecodeTimeTicks, encoded,
            sourceTrackEncrypted, sourceFragmentHasEncryptionMetadata)
    }

    private fun writePrepared(
        trackId: Long,
        sequenceNumber: Long,
        baseDecodeTimeTicks: Long,
        samples: List<EncodedSample>,
        sourceTrackEncrypted: Boolean,
        sourceFragmentHasEncryptionMetadata: Boolean
    ): ByteArray {
        require(!sourceTrackEncrypted) {
            "Clear CMAF writer rejects protected source tracks"
        }
        require(!sourceFragmentHasEncryptionMetadata) {
            "Clear CMAF writer rejects fragment encryption metadata"
        }
        require(trackId in 1..UINT32_MAX) { "Track ID is outside uint32 range" }
        require(sequenceNumber in 1..UINT32_MAX) { "Fragment sequence number is outside uint32 range" }
        require(baseDecodeTimeTicks >= 0) { "Decode time must not be negative" }
        require(samples.isNotEmpty()) { "At least one media sample is required" }

        var mediaBytes = 0L
        samples.forEachIndexed { index, sample ->
            require(sample.size > 0) { "Sample $index is empty" }
            require(sample.durationTicks in 1..UINT32_MAX) {
                "Sample $index duration is outside the supported uint32 range"
            }
            require(sample.compositionTimeOffsetTicks in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "Sample $index composition offset is outside signed trun range"
            }
            require(sample.sampleFlags in 0..UINT32_MAX) {
                "Sample $index flags are outside uint32 range"
            }
            mediaBytes = Math.addExact(mediaBytes, sample.size.toLong())
        }
        require(mediaBytes <= Int.MAX_VALUE - 8L) { "mdat payload exceeds the supported box size" }

        val mfhd = fullBox("mfhd", 0, 0, uint32(sequenceNumber))
        // default-base-is-moof plus explicit sample_description_index=1.
        val tfhd = fullBox("tfhd", 0, 0x020002,
            uint32(trackId) + uint32(1))
        val tfdt = fullBox("tfdt", 1, 0, uint64(baseDecodeTimeTicks))

        // The data offset is relative to the moof base and points just beyond the mdat header.
        val provisionalTrun = buildTrun(samples, 0)
        val provisionalTraf = box("traf", tfhd + tfdt + provisionalTrun)
        val provisionalMoof = box("moof", mfhd + provisionalTraf)
        val dataOffset = Math.addExact(provisionalMoof.size.toLong(), 8L)
        require(dataOffset <= Int.MAX_VALUE.toLong()) { "trun data_offset exceeds signed int range" }

        val trun = buildTrun(samples, dataOffset.toInt())
        val traf = box("traf", tfhd + tfdt + trun)
        val moof = box("moof", mfhd + traf)
        require(moof.size.toLong() + 8L == dataOffset) {
            "CMAF moof size changed while resolving sample data offset"
        }
        // Samples are already owned by the HEVC composer. Building mdat via a
        // ByteArrayOutputStream, toByteArray(), box() and moof + mdat allocated
        // four additional full-size payloads for every archive segment.
        // Write straight into the only output buffer instead; the bytes and
        // trun data_offset are identical, and no encrypted input is accepted.
        val totalBytes = Math.addExact(Math.addExact(moof.size.toLong(), 8L), mediaBytes)
        require(totalBytes <= Int.MAX_VALUE) { "Composed CMAF fragment exceeds the supported buffer size" }
        val result = ByteArray(totalBytes.toInt())
        moof.copyInto(result)
        val output = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        output.position(moof.size)
        output.putInt((mediaBytes + 8L).toInt())
        output.put('m'.code.toByte())
        output.put('d'.code.toByte())
        output.put('a'.code.toByte())
        output.put('t'.code.toByte())
        samples.forEach { sample -> sample.writePayload(output) }
        check(output.position() == result.size) { "CMAF sample payload did not fill its validated size" }
        return result
    }

    private fun buildTrun(samples: List<EncodedSample>, dataOffset: Int): ByteArray {
        val payload = ByteArrayOutputStream(8 + samples.size * 16)
        payload.write(uint32(samples.size.toLong()))
        payload.write(uint32(dataOffset.toLong()))
        samples.forEach { sample ->
            payload.write(uint32(sample.durationTicks))
            payload.write(uint32(sample.size.toLong()))
            payload.write(uint32(sample.sampleFlags))
            payload.write(uint32(sample.compositionTimeOffsetTicks.toInt().toLong() and UINT32_MAX))
        }
        // version 1 for signed composition offsets. Flags: data_offset, duration, size, flags, CTO.
        return fullBox("trun", 1, 0x000f01, payload.toByteArray())
    }

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            version.toByte(),
            (flags ushr 16).toByte(),
            (flags ushr 8).toByte(),
            flags.toByte()
        )
        return box(type, header + payload)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        require(type.length == 4) { "ISO-BMFF box type must contain four characters" }
        val size = payload.size.toLong() + 8L
        require(size <= UINT32_MAX) { "ISO-BMFF box exceeds 32-bit size" }
        return uint32(size) + type.toByteArray(Charsets.US_ASCII) + payload
    }

    private fun uint32(value: Long): ByteArray {
        require(value in 0..UINT32_MAX) { "Value is outside uint32 range" }
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
    }

    private fun uint64(value: Long): ByteArray {
        require(value >= 0) { "Value is outside supported non-negative uint64 range" }
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
    }

    private const val UINT32_MAX = 0xffff_ffffL
}
