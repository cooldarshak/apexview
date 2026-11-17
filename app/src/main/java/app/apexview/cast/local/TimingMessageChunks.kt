package app.apexview.cast.local

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/** Bounded optional timing messages, independently discardable without stopping video playback. */
internal object TimingMessageChunks {
    private const val CHUNK_BYTES = 16_000
    private const val MAX_BYTES = 128_000
    fun split(message: JSONObject): List<JSONObject> {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Timing display exceeds the bounded protocol budget" }
        if (bytes.size <= 30_000) return listOf(message)
        val id = UUID.randomUUID().toString()
        val count = (bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        return (0 until count).map { index -> JSONObject().put("kind", "chunk").put("id", id)
            .put("index", index).put("count", count)
            .put("data", Base64.getEncoder().encodeToString(bytes.copyOfRange(index * CHUNK_BYTES, minOf(bytes.size, (index + 1) * CHUNK_BYTES)))) }
    }
    class Assembler(private val nowMs: () -> Long) {
        private var id = ""
        private var expected = 0
        private var count = 0
        private var started = 0L
        private var bytes = ByteArrayOutputStream()
        fun clear() { id = ""; expected = 0; count = 0; bytes = ByteArrayOutputStream() }
        fun accept(message: JSONObject): JSONObject? {
            if (message.optString("kind") != "chunk") return message
            val nextId = message.getString("id").also { require(it.length <= 64) }
            val index = message.getInt("index")
            val nextCount = message.getInt("count").also { require(it in 1..8) }
            if (index == 0) { clear(); id = nextId; count = nextCount; started = nowMs() }
            require(id == nextId && count == nextCount && index == expected && nowMs() - started in 0..5_000) { "Stale or incomplete timing message" }
            val encoded = message.getString("data").also { require(it.length <= 21_336) }
            val part = Base64.getDecoder().decode(encoded)
            require(part.size <= CHUNK_BYTES && bytes.size() + part.size <= MAX_BYTES)
            bytes.write(part); expected++
            if (expected != count) return null
            val result = JSONObject(bytes.toString("UTF-8")); clear()
            return result
        }
    }
}
