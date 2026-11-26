package app.apexview.scene

/** MediaCodec AVC output may use Annex B or AVCC length-prefix framing. MPEG-TS needs Annex B. */
internal object BakedAvcAccessUnit {
    /** Final owned buffer, with optional parameter sets already in its prefix.
     * Never mutate MediaCodec's buffer position or retain it after release. */
    fun copyAnnexB(input: java.nio.ByteBuffer, offset: Int, size: Int, prefix: ByteArray,
                   nativeRequested: Boolean, report: (Boolean) -> Unit = {}): ByteArray {
        require(size in 1..48 * 1024 * 1024 && offset >= 0 && offset <= input.capacity() - size)
        val output = ByteArray(Math.addExact(prefix.size, size))
        prefix.copyInto(output)
        val actual = nativeRequested && app.apexview.tiled.NativeHotPath.avcAnnexB(
            input, offset, size, output, prefix.size)
        if (nativeRequested) report(actual)
        if (actual) return output
        val view = input.duplicate().apply { clear(); position(offset); limit(offset + size) }
        val annex = size >= 4 && view.get(offset) == 0.toByte() && view.get(offset + 1) == 0.toByte() &&
            (view.get(offset + 2) == 1.toByte() ||
                view.get(offset + 2) == 0.toByte() && view.get(offset + 3) == 1.toByte())
        if (annex) {
            view.get(output, prefix.size, size)
            return output
        }
        var at = prefix.size
        while (view.hasRemaining()) {
            require(view.remaining() >= 4) { "Truncated AVC NAL length" }
            val length = ((view.get().toInt() and 255) shl 24) or ((view.get().toInt() and 255) shl 16) or
                ((view.get().toInt() and 255) shl 8) or (view.get().toInt() and 255)
            require(length > 0 && length <= view.remaining()) { "Invalid AVC NAL length" }
            output[at] = 0; output[at + 1] = 0; output[at + 2] = 0; output[at + 3] = 1
            view.get(output, at + 4, length)
            at += 4 + length
        }
        return output
    }

    fun annexB(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty())
        if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            (bytes[2] == 1.toByte() || bytes[2] == 0.toByte() && bytes[3] == 1.toByte())) return bytes
        val output = java.io.ByteArrayOutputStream(bytes.size + 64)
        var at = 0
        while (at < bytes.size) {
            require(at + 4 <= bytes.size) { "Truncated AVC NAL length" }
            val length = ((bytes[at].toInt() and 255) shl 24) or ((bytes[at + 1].toInt() and 255) shl 16) or
                ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)
            require(length > 0 && length <= bytes.size - at - 4) { "Invalid AVC NAL length" }
            output.write(byteArrayOf(0, 0, 0, 1))
            output.write(bytes, at + 4, length)
            at += 4 + length
        }
        return output.toByteArray()
    }
}
