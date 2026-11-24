package app.apexview.cast

import java.io.ByteArrayOutputStream

/**
 * Muxes baked AVC pictures and selected AAC commentary on one 90 kHz clock.
 * A segment starts with an IDR unit containing SPS/PPS. Audio must use ADTS framing.
 * Keep a muxer per Cast session so continuity counters survive segment boundaries.
 */
internal class BakedCastTransportStream {
    data class Sample(val ptsUs: Long, val bytes: ByteArray, val video: Boolean, val keyFrame: Boolean = false)

    private val continuity = IntArray(8192)

    fun segment(samples: List<Sample>): ByteArray {
        require(samples.any { !it.video }) { "A baked Cast segment needs commentary" }
        val video = samples.filter { it.video }
        require(video.firstOrNull()?.keyFrame == true) { "A baked Cast segment must start with a video key frame" }
        require(samples.isNotEmpty() && samples.all { it.ptsUs >= 0 && it.bytes.isNotEmpty() })
        require(samples.filterNot { it.video }.all {
            it.bytes.size >= 7 && it.bytes[0].toInt() and 255 == 255 && it.bytes[1].toInt() and 246 == 240
        }) { "Baked Cast commentary requires AAC ADTS frames" }

        val output = ByteArrayOutputStream()
        packetize(output, 0, byteArrayOf(0) + section(byteArrayOf(
            0, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xf0.toByte(), 0,
        )))
        packetize(output, PMT, byteArrayOf(0) + section(byteArrayOf(
            2, 0xb0.toByte(), 23, 0, 1, 0xc1.toByte(), 0, 0,
            0xe1.toByte(), 0, 0xf0.toByte(), 0,
            0x1b, 0xe1.toByte(), 0, 0xf0.toByte(), 0,
            0x0f, 0xe1.toByte(), 1, 0xf0.toByte(), 0,
        )))
        for (sample in samples.sortedWith(compareBy<Sample> { it.ptsUs }.thenByDescending { it.video })) {
            // Leave acquisition lead time between the first PCR and the first presentation time.
            val pts = ((sample.ptsUs / 100 * 9) + ((sample.ptsUs % 100) * 9 / 100) + 9_000) and PTS_MASK
            val length = if (sample.video) 0 else sample.bytes.size + 8
            require(length <= 65535)
            val payload = byteArrayOf(0, 0, 1, (if (sample.video) 0xe0 else 0xc0).toByte(),
                (length ushr 8).toByte(), length.toByte(), 0x84.toByte(), 0x80.toByte(), 5) +
                timestamp(pts) + sample.bytes
            packetize(output, if (sample.video) VIDEO else AUDIO, payload,
                pcr = if (sample.video) (pts - 9_000) and PTS_MASK else null,
                randomAccess = sample.video && sample.keyFrame)
        }
        return output.toByteArray()
    }

    private fun packetize(output: ByteArrayOutputStream, pid: Int, payload: ByteArray, pcr: Long? = null,
                          randomAccess: Boolean = false) {
        var offset = 0
        while (offset < payload.size) {
            val first = offset == 0
            val clock = pcr.takeIf { first }
            val random = first && randomAccess
            val minimumAdaptation = if (clock != null) 8 else if (random) 2 else 0
            val size = minOf(payload.size - offset, 184 - minimumAdaptation)
            val adaptation = 184 - size
            output.write(0x47)
            output.write((if (first) 0x40 else 0) or (pid ushr 8))
            output.write(pid and 255)
            output.write((if (adaptation == 0) 0x10 else 0x30) or continuity[pid])
            continuity[pid] = (continuity[pid] + 1) and 15
            if (adaptation > 0) {
                output.write(adaptation - 1)
                if (adaptation > 1) {
                    output.write((if (clock != null) 0x10 else 0) or (if (random) 0x40 else 0))
                    if (clock != null) {
                        for (shift in listOf(25, 17, 9, 1)) output.write((clock ushr shift).toInt() and 255)
                        output.write(((clock.toInt() and 1) shl 7) or 0x7e)
                        output.write(0)
                    }
                    repeat(adaptation - 2 - if (clock != null) 6 else 0) { output.write(255) }
                }
            }
            output.write(payload, offset, size)
            offset += size
        }
    }

    private fun timestamp(value: Long) = byteArrayOf(
        (0x21 or ((value ushr 29).toInt() and 14)).toByte(), (value ushr 22).toByte(),
        (1 or ((value ushr 14).toInt() and 254)).toByte(), (value ushr 7).toByte(),
        (1 or ((value shl 1).toInt() and 254)).toByte(),
    )

    private fun section(bytes: ByteArray): ByteArray {
        var crc = -1
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 24)
            repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        }
        return bytes + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private companion object {
        const val PMT = 0x1000
        const val VIDEO = 0x100
        const val AUDIO = 0x101
        const val PTS_MASK = 0x1ffffffffL
    }
}
