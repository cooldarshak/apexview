package app.apexview.cast

import android.net.Uri
import app.apexview.tiled.CmafHttpReader
import app.apexview.tiled.HevcArchiveVodSession
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Serves one composed archive HEVC stream to Google's standard media receiver over the LAN. */
internal class SceneCastRelay(val session: HevcArchiveVodSession,
                             private val reader: CmafHttpReader, val address: String) : AutoCloseable {
    private val random = SecureRandom()
    private val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 16, CastRelayBinding.address(address))
    /** Direct HLS URL loaded by Google's Default Media Receiver after the Cast session starts. */
    val googleCastMediaUrl get() = "http://$address:${server.localPort}/s/$token/master.m3u8"
    private val workers = java.util.concurrent.ThreadPoolExecutor(4, 4, 0, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(8))
    private val accepting = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    private val lease = session.acquireLease()
    private val resources = ConcurrentHashMap<String, Uri>()
    private val audioManifests = ConcurrentHashMap<String, ByteArray>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var selectedAudio = session.audioUri
    init { accepting.execute {
        while (!stopped.get()) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            socket.soTimeout = 8_000
            if (activeSockets.size >= 12) { socket.close(); continue }
            activeSockets += socket
            try { workers.execute { socket.use { runCatching { serve(it) }; activeSockets -= it } } }
            catch (_: RuntimeException) { socket.close(); activeSockets -= socket }
        }
    } }

    fun selectAudio(label: String) { selectedAudio = session.audioUris[label] ?: selectedAudio }

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val out = ByteArrayOutputStream()
            while (out.size() < 4096) {
                val c = input.read(); if (c == -1 || c == 10) break
                if (c != 13) out.write(c)
            }
            require(out.size() < 4096)
            return out.toString("US-ASCII")
        }
        val request = line().split(' ')
        if (request.size != 3) return
        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val l = line(); if (l.isEmpty()) break
            total += l.length; require(total < 16_384)
            val at = l.indexOf(':'); if (at > 0) headers[l.take(at).lowercase()] = l.substring(at + 1).trim()
        }
        fun reply(status: Int, mime: String, body: ByteArray, extra: String = "") {
            val phrase = when (status) { 200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Bad Request" }
            val output = socket.getOutputStream()
            output.write(("HTTP/1.1 $status $phrase\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Range, Content-Type, Accept\r\nAccess-Control-Allow-Private-Network: true\r\n" +
                "Access-Control-Expose-Headers: Accept-Ranges, Content-Length, Content-Range\r\n" +
                "Connection: close\r\n$extra\r\n").toByteArray())
            output.write(body); output.flush()
        }
        val path = request[1].substringBefore('?')
        if (request[0] == "OPTIONS") {
            if (path.startsWith("/s/$token/")) reply(204, "text/plain", ByteArray(0))
            else reply(404, "text/plain", ByteArray(0))
            return
        }
        if (request[0] != "GET" || !path.startsWith("/s/$token/")) {
            reply(404, "text/plain", ByteArray(0)); return
        }
        val name = path.removePrefix("/s/$token/")
        try {
            when {
                name == "master.m3u8" -> reply(200, HLS, ("#EXTM3U\n#EXT-X-VERSION:7\n" +
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Selected audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=8000000,CODECS=\"${session.codecString()},mp4a.40.2\",RESOLUTION=${session.width}x${session.height},AUDIO=\"audio\"\nvideo.m3u8\n").toByteArray())
                name == "video.m3u8" -> reply(200, HLS, session.hlsPlaylist().toByteArray())
                name == "audio.m3u8" -> reply(200, HLS, audioManifest())
                name == "init.mp4" -> reply(200, "video/mp4", session.initialization())
                name.matches(Regex("s-\\d+\\.m4s")) -> reply(200, "video/mp4", session.segment(name.removePrefix("s-").removeSuffix(".m4s").toInt()))
                name.startsWith("a-") && resources[name] != null -> {
                    val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it) }
                    if (headers["range"] != null && range == null) { reply(400, "text/plain", ByteArray(0)); return }
                    val offset = range?.groupValues?.get(1)?.toLong() ?: 0L
                    val length = range?.let { it.groupValues[2].toLong() - offset + 1 } ?: androidx.media3.common.C.LENGTH_UNSET.toLong()
                    val bytes = reader.read(resources.getValue(name), 8 * 1024 * 1024, offset, length).bytes
                    val extra = if (range == null) "" else "Content-Range: bytes $offset-${offset + bytes.size - 1}/*\r\n"
                    reply(if (range == null) 200 else 206, "application/octet-stream", bytes, extra)
                }
                else -> reply(404, "text/plain", ByteArray(0))
            }
        } catch (_: Exception) { reply(400, "text/plain", "Receiver media is unavailable or incompatible".toByteArray()) }
    }

    /** Validate protection before exposing a link. No key URI or F1 credential is ever relayed. */
    fun validateAudio() { audioManifest() }
    private fun audioManifest(): ByteArray {
        val uri = requireNotNull(selectedAudio) { "No native audio rendition" }
        return audioManifests.getOrPut(uri.toString()) {
            val response = reader.read(uri, 2 * 1024 * 1024)
            try {
                val text = response.bytes.toString(Charsets.UTF_8)
                require(text.startsWith("#EXTM3U") && text.contains("#EXT-X-ENDLIST"))
                require(text.lineSequence().none { it.startsWith("#EXT-X-KEY:") && it.trim() != "#EXT-X-KEY:METHOD=NONE" } &&
                    !text.contains("#EXT-X-SESSION-KEY")) { "Protected audio needs a protected receiver" }
                Regex("#EXT-X-MAP:[^\n]*URI=\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.let { value ->
                    val init = reader.read(androidx.media3.common.util.UriUtil.resolveToUri(response.uri.toString(), value), 2 * 1024 * 1024).bytes
                    try {
                        val report = app.apexview.tiled.CmafBoxInspector.inspect(init)
                        require(report.initializationSegmentPresent && report.errors.isEmpty() && report.tracks.isNotEmpty() && report.tracks.none { it.encrypted }) {
                            "Audio initialization is incompatible with the clear receiver"
                        }
                    } finally { init.fill(0) }
                }
                fun local(value: String): String {
                    val resolved = androidx.media3.common.util.UriUtil.resolveToUri(response.uri.toString(), value)
                    val id = "a-" + MessageDigest.getInstance("SHA-256").digest(resolved.toString().toByteArray()).take(12).joinToString("") { "%02x".format(it) }
                    resources[id] = resolved
                    return id
                }
                val safeTags = listOf("#EXTM3U", "#EXT-X-VERSION:", "#EXT-X-TARGETDURATION:", "#EXT-X-MEDIA-SEQUENCE:",
                    "#EXT-X-DISCONTINUITY-SEQUENCE:", "#EXT-X-PLAYLIST-TYPE:", "#EXT-X-PROGRAM-DATE-TIME:",
                    "#EXT-X-BYTERANGE:", "#EXT-X-MAP:", "#EXTINF:", "#EXT-X-ENDLIST", "#EXT-X-DISCONTINUITY")
                text.lineSequence().filter { !it.startsWith('#') || safeTags.any(it::startsWith) }.joinToString("\n") { line ->
                    if (line.isNotBlank() && !line.startsWith('#')) local(line.trim())
                    else Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${local(it.groupValues[1])}\"" }
                }.toByteArray()
            } finally { response.bytes.fill(0) }
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        server.close(); activeSockets.forEach { runCatching { it.close() } }
        accepting.shutdownNow(); workers.shutdownNow(); lease.close()
        audioManifests.values.forEach { it.fill(0) }; audioManifests.clear(); resources.clear()
    }
    companion object { private const val HLS = "application/vnd.apple.mpegurl" }
}
