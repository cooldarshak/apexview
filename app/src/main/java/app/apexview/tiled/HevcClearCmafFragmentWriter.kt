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

    fun write(
        trackId: Long,
        sequenceNumber: Long,
        baseDecodeTimeTicks: Long,
        samples: List<Sample>,
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
            require(sample.data.isNotEmpty()) { "Sample $index is empty" }
            require(sample.durationTicks in 1..UINT32_MAX) {
                "Sample $index duration is outside the supported uint32 range"
            }
            require(sample.compositionTimeOffsetTicks in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "Sample $index composition offset is outside signed trun range"
            }
            require(sample.sampleFlags in 0..UINT32_MAX) {
                "Sample $index flags are outside uint32 range"
            }
            mediaBytes = Math.addExact(mediaBytes, sample.data.size.toLong())
        }
        require(mediaBytes <= Int.MAX_VALUE - 8L) { "mdat payload exceeds the supported box size" }

        val samplePayload = ByteArrayOutputStream(mediaBytes.toInt())
        samples.forEach { samplePayload.write(sample.data) }
        val mediaData = samplePayload.toByteArray()

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
        val mdat = box("mdat", mediaData)
        return moof + mdat
    }

    private fun buildTrun(samples: List<Sample>, dataOffset: Int): ByteArray {
        val payload = ByteArrayOutputStream(8 + samples.size * 16)
        payload.write(uint32(samples.size.toLong()))
        payload.write(uint32(dataOffset.toLong()))
        samples.forEach { sample ->
            payload.write(uint32(sample.durationTicks))
            payload.write(uint32(sample.data.size.toLong()))
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
