package app.apexview.cast

import android.net.Uri
import app.apexview.tiled.CmafHttpReader
import app.apexview.tiled.HevcArchiveVodSession
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Serves one composed archive HEVC stream to Google's standard media receiver over the LAN. */
internal class SceneCastRelay(val session: HevcArchiveVodSession,
                             private val reader: CmafHttpReader, val address: String,
                             private val peer: InetAddress? = null) : AutoCloseable {
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
    private data class CaptionTrack(val index: Int, val masterTag: String, val playlist: ByteArray,
                                    val segments: List<Uri>)
    @Volatile private var captions: List<CaptionTrack> = emptyList()
    init { accepting.execute {
        while (!stopped.get()) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            socket.soTimeout = 8_000
            if ((peer != null && socket.inetAddress != peer) || activeSockets.size >= 12) { socket.close(); continue }
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
        if (request.size != 3 || request[2] !in setOf("HTTP/1.0", "HTTP/1.1")) return
        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val l = line(); if (l.isEmpty()) break
            total += l.length; require(total < 16_384)
            val at = l.indexOf(':')
            if (at > 0) {
                val key = l.take(at).lowercase()
                if (key == "range") require(key !in headers) { "Repeated range" }
                headers[key] = l.substring(at + 1).trim()
            }
        }
        var responseStarted = false
        fun replyHeaders(status: Int, mime: String, length: Int, extra: String = ""): java.io.OutputStream {
            val phrase = when (status) { 200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 416 -> "Range Not Satisfiable"; 403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Bad Request" }
            val output = socket.getOutputStream()
            responseStarted = true
            output.write(("HTTP/1.1 $status $phrase\r\nContent-Type: $mime\r\nContent-Length: $length\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Range, Content-Type, Accept\r\nAccess-Control-Allow-Private-Network: true\r\n" +
                "Access-Control-Expose-Headers: Accept-Ranges, Content-Length, Content-Range\r\n" +
                "Connection: close\r\n$extra\r\n").toByteArray())
            return output
        }
        fun reply(status: Int, mime: String, body: ByteArray, extra: String = "") {
            val output = replyHeaders(status, mime, body.size, extra)
            if (request[0] != "HEAD") output.write(body)
            output.flush()
        }
        fun replyVideo(body: app.apexview.tiled.ArchiveFragmentLease) {
            body.use { lease ->
                val range = headers["range"]
                val selected = RelayHttpRange.parse(range, lease.size)
                if (selected == null) {
                    reply(416, "text/plain", ByteArray(0), "Content-Range: bytes */${lease.size}\r\n")
                    return
                }
                val extra = "Accept-Ranges: bytes\r\n" + if (range == null) ""
                    else "Content-Range: bytes ${selected.start}-${selected.end}/${lease.size}\r\n"
                val output = replyHeaders(if (range == null) 200 else 206, "video/mp4", selected.length, extra)
                if (request[0] == "GET") lease.writeTo(output, selected.start, selected.length)
                output.flush()
            }
        }
        val path = request[1].substringBefore('?')
        if (request[0] == "OPTIONS") {
            if (path.startsWith("/s/$token/")) reply(204, "text/plain", ByteArray(0))
            else reply(404, "text/plain", ByteArray(0))
            return
        }
        if (request[0] !in setOf("GET", "HEAD") || !path.startsWith("/s/$token/")) {
            reply(404, "text/plain", ByteArray(0)); return
        }
        val name = path.removePrefix("/s/$token/")
        try {
            when {
                name == "master.m3u8" -> {
                    val tracks = captions
                    reply(200, HLS, ("#EXTM3U\n#EXT-X-VERSION:7\n" +
                        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Selected audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n" +
                        tracks.joinToString("") { it.masterTag } +
                        "#EXT-X-STREAM-INF:BANDWIDTH=8000000,CODECS=\"${session.codecString()},mp4a.40.2\",RESOLUTION=${session.width}x${session.height},AUDIO=\"audio\"" +
                        (if (tracks.isEmpty()) "" else ",SUBTITLES=\"pitwall-cc\"") + "\nvideo.m3u8\n").toByteArray())
                }
                name == "video.m3u8" -> reply(200, HLS, session.hlsPlaylist().toByteArray())
                name == "audio.m3u8" -> reply(200, HLS, audioManifest())
                name.matches(Regex("cc-[0-9]+[.]m3u8")) -> {
                    val index = name.removePrefix("cc-").removeSuffix(".m3u8").toIntOrNull()
                    val track = captions.firstOrNull { it.index == index }
                    if (track == null) reply(404, "text/plain", ByteArray(0))
                    else reply(200, HLS, track.playlist)
                }
                name.matches(Regex("cc-[0-9]+-[0-9]+[.]vtt")) -> {
                    val ids = name.removePrefix("cc-").removeSuffix(".vtt").split('-')
                    val track = captions.firstOrNull { it.index == ids[0].toIntOrNull() }
                    val uri = ids[1].toIntOrNull()?.let { track?.segments?.getOrNull(it) }
                    if (uri == null) reply(404, "text/plain", ByteArray(0))
                    else {
                        val bytes = reader.read(uri, MAX_CAPTION_BYTES).bytes
                        try {
                            require(ClearCaptionPlaylist.isWebVtt(bytes)) { "Caption segment is not clear WebVTT" }
                            reply(200, "text/vtt", bytes)
                        } finally { bytes.fill(0) }
                    }
                }
                name == "init.mp4" -> replyVideo(app.apexview.tiled.ArchiveFragmentLease.owned(session.initialization()))
                name.matches(Regex("s-\\d+\\.m4s")) -> replyVideo(session.segmentLease(name.removePrefix("s-").removeSuffix(".m4s").toInt()))
                name.startsWith("a-") && resources[name] != null -> {
                    val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it) }
                    if (headers["range"] != null && range == null) { reply(400, "text/plain", ByteArray(0)); return }
                    val offset = if (range == null) 0L else requireNotNull(range.groupValues[1].toLongOrNull())
                    val length = if (range == null) androidx.media3.common.C.LENGTH_UNSET.toLong() else {
                        val last = requireNotNull(range.groupValues[2].toLongOrNull())
                        require(last >= offset && last - offset < 8L * 1024 * 1024 && last < Long.MAX_VALUE)
                        last - offset + 1
                    }
                    val bytes = reader.read(resources.getValue(name), 8 * 1024 * 1024, offset, length).bytes
                    try {
                        if (range != null) require(bytes.size.toLong() == length)
                        val extra = if (range == null) "" else "Content-Range: bytes $offset-${offset + bytes.size - 1}/*\r\n"
                        reply(if (range == null) 200 else 206, "application/octet-stream", bytes, extra)
                    } finally { bytes.fill(0) }
                }
                else -> reply(404, "text/plain", ByteArray(0))
            }
        } catch (_: Exception) {
            if (!responseStarted) reply(400, "text/plain", "Receiver media is unavailable or incompatible".toByteArray())
        }
    }

    /** Validate protection before exposing a link. No key URI or F1 credential is ever relayed. */
    fun validateAudio() { audioManifest(); prepareCaptions() }

    private fun prepareCaptions() {
        val offers = session.captionSource?.renditions.orEmpty().take(16)
        val verified = mutableListOf<CaptionTrack>()
        var remainingSegments = 32_768
        for ((index, offer) in offers.withIndex()) {
            if (stopped.get() || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Caption preparation cancelled")
            try {
                val response = reader.read(offer.uri, 2 * 1024 * 1024)
                try {
                    val playlist = ClearCaptionPlaylist.rewrite(response.bytes.toString(Charsets.UTF_8), index)
                    require(playlist.sourceUris.size <= remainingSegments)
                    val segments = playlist.sourceUris.map { raw ->
                        androidx.media3.common.util.UriUtil.resolveToUri(response.uri.toString(), raw).also {
                            require((it.scheme == "https" || it.scheme == "http") &&
                                !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null)
                        }
                    }
                    // Confirm the advertised rendition really contains WebVTT.
                    // Every later request also checks its exact header; no key
                    // URI, credentials or unverified binary payload is returned.
                    val sample = reader.read(segments.first(), MAX_CAPTION_BYTES).bytes
                    try { require(ClearCaptionPlaylist.isWebVtt(sample)) }
                    finally { sample.fill(0) }
                    verified += CaptionTrack(index, ClearCaptionPlaylist.masterTag(index, offer.language, offer.label),
                        playlist.text.toByteArray(Charsets.UTF_8), segments)
                    remainingSegments -= segments.size
                } finally { response.bytes.fill(0) }
            } catch (cancelled: java.io.InterruptedIOException) { throw cancelled }
            catch (error: Exception) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Caption preparation cancelled")
                android.util.Log.w("PitwallCast", "CLEAR_CAPTION_RENDITION_UNAVAILABLE type=${error.javaClass.simpleName}")
            }
        }
        check(!stopped.get())
        captions = verified.toList()
        android.util.Log.i("PitwallCast", "CLEAR_CAPTION_RELAY verified=${verified.size} declared=${offers.size}")
    }
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
        captions = emptyList()
    }
    companion object {
        private const val HLS = "application/vnd.apple.mpegurl"
        private const val MAX_CAPTION_BYTES = 1024 * 1024
    }
}
