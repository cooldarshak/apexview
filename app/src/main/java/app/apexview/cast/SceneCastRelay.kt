package app.apexview.cast

import android.content.Context
import android.net.Uri
import app.apexview.scene.VideoScene
import app.apexview.tiled.CmafHttpReader
import app.apexview.tiled.HevcArchiveVodSession
import org.json.JSONArray
import org.json.JSONObject
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

/**
 * Receiver-driven LAN casting: one HEVC atlas + native audio, window geometry and UI-only images.
 * Never captures/encodes video from the screen, exports F1 credentials or forwards a key/license.
 * The receiver must support clear HEVC MSE/HLS + WebGL. Google Cast launch is a separate adapter.
 */
internal class SceneCastRelay(private val context: Context, val session: HevcArchiveVodSession,
                             private val reader: CmafHttpReader, val address: String) : AutoCloseable {
    private val random = SecureRandom()
    private val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    val pairingCode = (100000 + random.nextInt(900000)).toString()
    private val server = ServerSocket(0)
    val receiverUrl get() = "http://$address:${server.localPort}/"
    private val workers = java.util.concurrent.ThreadPoolExecutor(4, 4, 0, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(8))
    private val accepting = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    private val lease = session.acquireLease()
    private val failures = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val resources = ConcurrentHashMap<String, Uri>()
    private val audioManifests = ConcurrentHashMap<String, ByteArray>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var selectedAudio = session.audioUri
    @Volatile var sceneJson: String = "{}"
        private set
    @Volatile var lastReceiverAt: Long = 0
        private set
    private data class Overlay(val positionMs: Long, val bytes: ByteArray)
    private val overlays = ArrayDeque<Overlay>()

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

    fun publish(scene: VideoScene, positionMs: Long, playing: Boolean, speed: Float, muted: Boolean, revision: Int, audioRevision: Int) {
        scene.quads() // Validate before anything is sent to the receiver.
        sceneJson = JSONObject().put("version", 1).put("width", scene.width).put("height", scene.height)
            .put("atlasWidth", scene.atlasWidth).put("atlasHeight", scene.atlasHeight).put("sources", scene.sources)
            .put("codec", session.codecString()).put("positionMs", positionMs).put("playing", playing)
            .put("speed", speed).put("muted", muted).put("revision", revision).put("audioRevision", audioRevision)
            .put("windows", JSONArray(scene.windows.map { w -> JSONObject().put("id", w.id).put("source", w.source)
                .put("x", w.x).put("y", w.y).put("width", w.width).put("height", w.height).put("z", w.z) })).toString()
    }

    fun publishOverlay(positionMs: Long, png: ByteArray) {
        if (png.size > 2 * 1024 * 1024 || stopped.get()) return
        synchronized(overlays) {
            overlays += Overlay(positionMs, png)
            while (overlays.size > 24 || overlays.sumOf { it.bytes.size } > 8 * 1024 * 1024) overlays.removeFirst().bytes.fill(0)
        }
    }
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
            val phrase = when (status) { 200 -> "OK"; 206 -> "Partial Content"; 403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Bad Request" }
            val output = socket.getOutputStream()
            output.write(("HTTP/1.1 $status $phrase\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n$extra\r\n").toByteArray())
            output.write(body); output.flush()
        }
        val path = request[1].substringBefore('?')
        if (path == "/" && request[0] == "GET") {
            reply(200, "text/html; charset=utf-8", context.assets.open("cast/pair.html").use { it.readBytes() }); return
        }
        if (path == "/pair" && request[0] == "POST") {
            val ip = socket.inetAddress.hostAddress.orEmpty(); val now = System.currentTimeMillis()
            val failed = failures[ip]?.takeIf { now - it.first < 60_000 }
            if ((failed?.second ?: 0) >= 5) { reply(429, "text/plain", "Try again in one minute".toByteArray()); return }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length !in 1..32) { reply(400, "text/plain", ByteArray(0)); return }
            val body = ByteArray(length)
            var at = 0; while (at < length) { val n = input.read(body, at, length - at); if (n < 0) return; at += n }
            if (!MessageDigest.isEqual(body, pairingCode.toByteArray())) {
                failures.compute(ip) { _, old -> val recent = old?.takeIf { now - it.first < 60_000 }; (recent?.first ?: now) to ((recent?.second ?: 0) + 1) }
                reply(403, "text/plain", "Incorrect pairing code".toByteArray()); return
            }
            reply(200, "text/plain", "/s/$token/receiver.html".toByteArray()); return
        }
        if (request[0] != "GET" || !path.startsWith("/s/$token/")) {
            reply(404, "text/plain", ByteArray(0)); return
        }
        lastReceiverAt = System.currentTimeMillis()
        val name = path.removePrefix("/s/$token/")
        try {
            when {
                name in setOf("receiver.html", "receiver.mjs", "receiver-model.mjs") -> reply(200, if (name.endsWith("js")) "application/javascript" else "text/html; charset=utf-8",
                    context.assets.open("cast/$name").use { it.readBytes() })
                name == "scene.json" -> reply(200, "application/json", sceneJson.toByteArray())
                name == "master.m3u8" -> reply(200, HLS, ("#EXTM3U\n#EXT-X-VERSION:7\n" +
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Selected audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=8000000,CODECS=\"${session.codecString()},mp4a.40.2\",RESOLUTION=${session.width}x${session.height},AUDIO=\"audio\"\nvideo.m3u8\n").toByteArray())
                name == "video.m3u8" -> reply(200, HLS, session.hlsPlaylist().toByteArray())
                name == "audio.m3u8" -> reply(200, HLS, audioManifest())
                name == "init.mp4" -> reply(200, "video/mp4", session.initialization())
                name.matches(Regex("s-\\d+\\.m4s")) -> reply(200, "video/mp4", session.segment(name.removePrefix("s-").removeSuffix(".m4s").toInt()))
                name == "overlay.png" -> {
                    val time = Uri.parse(request[1]).getQueryParameter("t")?.toLongOrNull() ?: 0
                    val image = synchronized(overlays) { overlays.minByOrNull { kotlin.math.abs(it.positionMs - time) }?.bytes?.copyOf() }
                    if (image == null) reply(404, "text/plain", ByteArray(0)) else reply(200, "image/png", image)
                }
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
        synchronized(overlays) { overlays.forEach { it.bytes.fill(0) }; overlays.clear() }
        audioManifests.values.forEach { it.fill(0) }; audioManifests.clear(); resources.clear()
    }
    companion object { private const val HLS = "application/vnd.apple.mpegurl" }
}
