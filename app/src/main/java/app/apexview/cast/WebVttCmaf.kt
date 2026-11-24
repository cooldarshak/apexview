package app.apexview.cast

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** ISO BMFF WebVTT track for Media3's fragmented MP4 text extractor. */
internal object WebVttCmaf {
    const val TIMESCALE = 1_000_000
    private const val MAX_SAMPLE_BYTES = 1024 * 1024

    fun initialization(): ByteArray {
        val fileType = box("ftyp", bytes { writeBytes("iso6"); writeInt(0); writeBytes("iso6mp41dash") })
        val movieHeader = fullBox("mvhd", 0, bytes {
            writeInt(0); writeInt(0); writeInt(TIMESCALE); writeInt(0)
            writeInt(0x010000); writeShort(0x0100); write(ByteArray(10))
            matrix(); write(ByteArray(24)); writeInt(2)
        })
        val trackHeader = fullBox("tkhd", 3, bytes {
            writeInt(0); writeInt(0); writeInt(1); writeInt(0); writeInt(0)
            write(ByteArray(16)); matrix(); writeInt(0); writeInt(0)
        })
        val mediaHeader = fullBox("mdhd", 0, bytes {
            writeInt(0); writeInt(0); writeInt(TIMESCALE); writeInt(0); writeShort(0x55c4); writeShort(0)
        })
        val handler = fullBox("hdlr", 0, bytes { writeInt(0); writeBytes("text"); write(ByteArray(13)) })
        val entry = box("wvtt", bytes { write(ByteArray(6)); writeShort(1) } +
            box("vttC", "WEBVTT\n".toByteArray(Charsets.UTF_8)))
        val sampleTable = box("stbl", fullBox("stsd", 0, bytes { writeInt(1); write(entry) }) +
            fullBox("stts", 0, ByteArray(4)) + fullBox("stsc", 0, ByteArray(4)) +
            fullBox("stsz", 0, ByteArray(8)) + fullBox("stco", 0, ByteArray(4)))
        val references = fullBox("dref", 0, bytes { writeInt(1); write(fullBox("url ", 1, ByteArray(0))) })
        val mediaInfo = box("minf", fullBox("nmhd", 0, ByteArray(0)) + box("dinf", references) + sampleTable)
        val track = box("trak", trackHeader + box("mdia", mediaHeader + handler + mediaInfo))
        val defaults = fullBox("trex", 0, bytes { writeInt(1); writeInt(1); write(ByteArray(12)) })
        return fileType + box("moov", movieHeader + track + box("mvex", defaults))
    }

    fun fragment(sequence: Int, baseUs: Long, segmentStartUs: Long, segmentEndUs: Long,
                 cues: List<RollingWebVtt.Cue>): ByteArray {
        require(sequence > 0 && baseUs >= 0 && segmentEndUs > segmentStartUs &&
            segmentEndUs - segmentStartUs <= Int.MAX_VALUE)
        val clipped = cues.filter { it.endUs > segmentStartUs && it.startUs < segmentEndUs }
        val bounds = (listOf(segmentStartUs, segmentEndUs) + clipped.flatMap {
            listOf(maxOf(it.startUs, segmentStartUs), minOf(it.endUs, segmentEndUs))
        }).distinct().sorted()
        val payloads = clipped.associateWith { cue ->
            box("vttc", (cue.id?.let { box("iden", it.toByteArray()) } ?: ByteArray(0)) +
                box("sttg", cue.settings.toByteArray()) + box("payl", cue.text.toByteArray()))
        }
        val empty = box("vtte", ByteArray(0))
        val samples = bounds.zipWithNext().map { (start, end) ->
            val current = clipped.filter { it.startUs <= start && it.endUs >= end }
            (end - start).toInt() to current.map(payloads::getValue).ifEmpty { listOf(empty) }
        }
        val sizes = samples.map { (_, parts) -> parts.sumOf { it.size } }
        require(sizes.sumOf(Int::toLong) <= MAX_SAMPLE_BYTES) { "Fragmented captions exceed output cap" }
        val mfhd = fullBox("mfhd", 0, bytes { writeInt(sequence) })
        val tfhd = fullBox("tfhd", 0x020000, bytes { writeInt(1) })
        val tfdt = fullBox("tfdt", 0x01000000, bytes { writeLong(baseUs) })
        val runSize = 20 + samples.size * 8
        val offset = 8 + mfhd.size + 8 + tfhd.size + tfdt.size + runSize + 8
        val run = fullBox("trun", 0x0301, bytes {
            writeInt(samples.size); writeInt(offset)
            samples.forEachIndexed { index, (duration, _) ->
                writeInt(duration); writeInt(sizes[index])
            }
        })
        val moof = box("moof", mfhd + box("traf", tfhd + tfdt + run))
        return moof + box("mdat", bytes {
            samples.forEach { (_, parts) -> parts.forEach { write(it) } }
        })
    }

    private fun DataOutputStream.matrix() {
        listOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).forEach(::writeInt)
    }

    private fun fullBox(type: String, versionFlags: Int, payload: ByteArray) =
        box(type, bytes { writeInt(versionFlags); write(payload) })

    private fun box(type: String, payload: ByteArray) = bytes {
        writeInt(8 + payload.size); writeBytes(type); write(payload)
    }

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()
}
