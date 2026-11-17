package app.apexview.cast.local

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONObject

/** FCast v3's documented JSON/TCP framing. Pitwall extensions are explicitly negotiated. */
internal object FcastProtocol {
    const val SERVICE_TYPE = "_fcast._tcp."
    const val PORT = 46899
    const val MAX_PACKET = 32000
    const val PLAY = 1
    const val PAUSE = 2
    const val RESUME = 3
    const val STOP = 4
    const val SEEK = 5
    const val PLAYBACK_UPDATE = 6
    const val VOLUME_UPDATE = 7
    const val SET_VOLUME = 8
    const val ERROR = 9
    const val SET_SPEED = 10
    const val VERSION = 11
    const val PING = 12
    const val PONG = 13
    const val INITIAL = 14
    const val PLAY_UPDATE = 15
    // Private opcodes, sent only after the peer advertises pitwallScene=1.
    const val SCENE = 128
    const val RENDERING = 129

    data class Packet(val opcode: Int, val body: JSONObject? = null)

    fun read(input: InputStream): Packet? {
        val first = input.read()
        if (first < 0) return null
        var length = first.toLong()
        for (shift in 1..3) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Truncated FCast length")
            length = length or (byte.toLong() shl (shift * 8))
        }
        require(length in 1..MAX_PACKET.toLong()) { "Invalid FCast packet length" }
        val opcode = input.read()
        if (opcode < 0) throw EOFException("Missing FCast opcode")
        val bytes = ByteArray(length.toInt() - 1)
        var read = 0
        while (read < bytes.size) {
            val count = input.read(bytes, read, bytes.size - read)
            if (count < 0) throw EOFException("Truncated FCast body")
            if (count == 0) throw EOFException("Empty FCast read")
            read += count
        }
        return Packet(opcode, if (bytes.isEmpty()) null else JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    fun write(output: OutputStream, packet: Packet) {
        require(packet.opcode in 0..255)
        val bytes = packet.body?.toString()?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        val length = bytes.size + 1
        require(length <= MAX_PACKET) { "FCast packet exceeds 32 KB" }
        for (shift in 0..3) output.write((length ushr (shift * 8)) and 255)
        output.write(packet.opcode)
        output.write(bytes)
        output.flush()
    }
}
