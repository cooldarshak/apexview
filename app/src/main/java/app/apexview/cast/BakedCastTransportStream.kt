package app.apexview.cast

import app.apexview.tiled.NativeHotPath
import app.apexview.tiled.NativeFragmentLease

/**
 * Muxes baked AVC pictures and selected AAC commentary on one 90 kHz clock.
 * A segment starts with an IDR unit containing SPS/PPS. Audio must use ADTS framing.
 * Keep a muxer per Cast session so continuity counters survive segment boundaries.
 */
internal class BakedCastTransportStream(private val nativeRequested: () -> Boolean = { false }) {
    data class Sample(val ptsUs: Long, val bytes: ByteArray, val video: Boolean, val keyFrame: Boolean = false)

    private val continuity = IntArray(8192)
    private val pesHeader = ByteArray(14)
    private val pat = byteArrayOf(0) + section(byteArrayOf(
        0, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xf0.toByte(), 0,
    ))
    private val pmt = byteArrayOf(0) + section(byteArrayOf(
        2, 0xb0.toByte(), 23, 0, 1, 0xc1.toByte(), 0, 0,
        0xe1.toByte(), 0, 0xf0.toByte(), 0,
        0x1b, 0xe1.toByte(), 0, 0xf0.toByte(), 0,
        0x0f, 0xe1.toByte(), 1, 0xf0.toByte(), 0,
    ))

    private fun validate(samples: List<Sample>) {
        require(samples.any { !it.video }) { "A baked Cast segment needs commentary" }
        require(samples.firstOrNull { it.video }?.keyFrame == true) { "A baked Cast segment must start with a video key frame" }
        require(samples.all { it.ptsUs >= 0 && it.bytes.isNotEmpty() })
        require(samples.all { it.video || it.bytes.size >= 7 &&
            it.bytes[0].toInt() and 255 == 255 && it.bytes[1].toInt() and 246 == 240 &&
            it.bytes.size <= 65527
        }) { "Baked Cast commentary requires AAC ADTS frames" }

    }

    fun encodedSize(samples: List<Sample>): Int {
        var packetCount = 2L
        for (sample in samples) {
            val size = sample.bytes.size.toLong() + pesHeader.size
            val firstCapacity = if (sample.video) 176L else 184L
            packetCount = Math.addExact(packetCount, 1 + (maxOf(0L, size - firstCapacity) + 183L) / 184L)
        }
        return Math.toIntExact(Math.multiplyExact(packetCount, 188L))
    }

    private var transportActual: Boolean? = null
    private fun reportTransport(actual: Boolean, reason: String) {
        if (transportActual == actual) return
        transportActual = actual
        android.util.Log.i("PitwallNativeHotPath", "AUDIO_TRANSPORT_ENGINE requested=RUST actual=" +
            (if (actual) "RUST" else "JVM") + " reason=" + reason)
    }

    /** Both engines share the continuity state. Commit the native counters
     * only after complete bounded writing; a failed candidate is unpublished. */
    fun nativeSegment(samples: List<Sample>, context: android.content.Context?): NativeFragmentLease? {
        if (!nativeRequested()) { transportActual = null; return null }
        if (!NativeHotPath.available()) { reportTransport(false, "NATIVE_UNAVAILABLE"); return null }
        validate(samples)
        val ordered = samples.sortedWith(compareBy<Sample> { it.ptsUs }.thenByDescending { it.video })
        val parts = Array(ordered.size) { ordered[it].bytes }
        val meta = LongArray(ordered.size * 3)
        ordered.forEachIndexed { index, sample ->
            meta[3*index] = sample.ptsUs
            meta[3*index+1] = sample.bytes.size.toLong()
            meta[3*index+2] = (if (sample.video) 1L else 0L) or (if (sample.keyFrame) 2L else 0L)
        }
        val counts = intArrayOf(continuity[0], continuity[PMT], continuity[VIDEO], continuity[AUDIO])
        val result = try {
            NativeFragmentLease.build(encodedSize(samples), context) { buffer ->
                check(NativeHotPath.transport(parts, meta, counts, buffer)) { "Native TS extent rejected" }
                buffer.position(buffer.capacity())
            }
        } catch (_: IllegalStateException) { null }
        if (result == null) { reportTransport(false, "ADMISSION_OR_NATIVE_REJECTED"); return null }
        continuity[0] = counts[0]; continuity[PMT] = counts[1]
        continuity[VIDEO] = counts[2]; continuity[AUDIO] = counts[3]
        reportTransport(true, "DIRECT_LEASE_PUBLISHED")
        return result
    }

    fun segment(samples: List<Sample>): ByteArray {
        validate(samples)
        // Exact transport extent, no BAOS growth, clone or whole PES concat.
        val output = ByteArray(encodedSize(samples))
        var at = packetize(output, 0, 0, pat)
        at = packetize(output, at, PMT, pmt)
        val ordered = samples.sortedWith(compareBy<Sample> { it.ptsUs }.thenByDescending { it.video })
        for (sample in ordered) {
            val ticks = (sample.ptsUs / 100 * 9) + ((sample.ptsUs % 100) * 9 / 100)
            val pts = (ticks + 9_000) and PTS_MASK
            val length = if (sample.video) 0 else sample.bytes.size + 8
            pesHeader[0] = 0; pesHeader[1] = 0; pesHeader[2] = 1
            pesHeader[3] = (if (sample.video) 0xe0 else 0xc0).toByte()
            pesHeader[4] = (length ushr 8).toByte(); pesHeader[5] = length.toByte()
            pesHeader[6] = 0x84.toByte(); pesHeader[7] = 0x80.toByte(); pesHeader[8] = 5
            pesHeader[9] = (0x21 or ((pts ushr 29).toInt() and 14)).toByte()
            pesHeader[10] = (pts ushr 22).toByte()
            pesHeader[11] = (1 or ((pts ushr 14).toInt() and 254)).toByte()
            pesHeader[12] = (pts ushr 7).toByte()
            pesHeader[13] = (1 or ((pts shl 1).toInt() and 254)).toByte()
            at = packetize(output, at, if (sample.video) VIDEO else AUDIO, pesHeader,
                sample.bytes, if (sample.video) (pts - 9_000) and PTS_MASK else null,
                sample.video && sample.keyFrame)
        }
        check(at == output.size)
        return output
    }

    /** Packetize two borrowed extents without copying them into a PES array. */
    private fun packetize(output: ByteArray, start: Int, pid: Int, header: ByteArray,
        payload: ByteArray = EMPTY, pcr: Long? = null, randomAccess: Boolean = false): Int {
        var offset = 0
        var at = start
        val total = Math.addExact(header.size, payload.size)
        while (offset < total) {
            val first = offset == 0
            val clock = pcr.takeIf { first }
            val random = first && randomAccess
            val minimumAdaptation = if (clock != null) 8 else if (random) 2 else 0
            val size = minOf(total - offset, 184 - minimumAdaptation)
            val adaptation = 184 - size
            output[at++] = 0x47
            output[at++] = ((if (first) 0x40 else 0) or (pid ushr 8)).toByte()
            output[at++] = (pid and 255).toByte()
            output[at++] = ((if (adaptation == 0) 0x10 else 0x30) or continuity[pid]).toByte()
            continuity[pid] = (continuity[pid] + 1) and 15
            if (adaptation > 0) {
                output[at++] = (adaptation - 1).toByte()
                if (adaptation > 1) {
                    output[at++] = ((if (clock != null) 0x10 else 0) or (if (random) 0x40 else 0)).toByte()
                    if (clock != null) {
                        output[at++] = (clock ushr 25).toByte()
                        output[at++] = (clock ushr 17).toByte()
                        output[at++] = (clock ushr 9).toByte()
                        output[at++] = (clock ushr 1).toByte()
                        output[at++] = (((clock.toInt() and 1) shl 7) or 0x7e).toByte()
                        output[at++] = 0
                    }
                    val padding = adaptation - 2 - if (clock != null) 6 else 0
                    output.fill(0xff.toByte(), at, at + padding)
                    at += padding
                }
            }
            val prefix = minOf(size, (header.size - offset).coerceAtLeast(0))
            if (prefix > 0) header.copyInto(output, at, offset, offset + prefix)
            val rest = size - prefix
            if (rest > 0) {
                val from = offset + prefix - header.size
                payload.copyInto(output, at + prefix, from, from + rest)
            }
            at += size
            offset += size
        }
        return at
    }

    private fun section(bytes: ByteArray): ByteArray {
        var crc = -1
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 24)
            repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        }
        return bytes + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val PMT = 0x1000
        const val VIDEO = 0x100
        const val AUDIO = 0x101
        const val PTS_MASK = 0x1ffffffffL
    }
}
