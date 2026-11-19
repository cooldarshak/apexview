package app.apexview.scene

/** MediaCodec AVC output may use Annex B or AVCC length-prefix framing. MPEG-TS needs Annex B. */
internal object BakedAvcAccessUnit {
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
