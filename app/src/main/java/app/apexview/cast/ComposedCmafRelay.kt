package app.apexview.cast

import app.apexview.tiled.ProtectedHevcComposedDashPackageBuilder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded peer-only HTTP publication of unchanged CBCS CMAF and its public DRM metadata. */
internal class ComposedCmafRelay(address: String, private val peer: InetAddress?, private val liveHls: Boolean = false) : AutoCloseable {
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 16, CastRelayBinding.address(address))
    val url = "http://$address:${server.localPort}/p/$token/" + if (liveHls) "live.m3u8" else "manifest.mpd"
    private val accepting = Executors.newSingleThreadExecutor()
    private val workers = ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, ArrayBlockingQueue(6))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean(false)
    private val resources = RelayResourceStore()
    fun publish(next: ProtectedHevcComposedDashPackageBuilder.Package) = publishResources(next.resources)

    internal fun publishResources(next: Map<String, ByteArray>) {
        check(!closed.get())
        resources.publish(next)
    }
    init { accepting.execute {
        while (!closed.get()) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            if (peer != null && socket.inetAddress != peer || sockets.size >= 9) { socket.close(); continue }
            socket.soTimeout = 8_000; sockets += socket
            try { workers.execute { socket.use { runCatching { serve(it) }; sockets -= it } } }
            catch (_: RuntimeException) { sockets -= socket; socket.close() }
        }
    } }
    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 4096) { val c = input.read(); if (c == -1 || c == 10) break; if (c != 13) bytes.write(c) }
            require(bytes.size() < 4096); return bytes.toString("US-ASCII")
        }
        val request = line().split(' '); require(request.size == 3 && request[2] in setOf("HTTP/1.0", "HTTP/1.1"))
        var total = 0; var range: String? = null
        while (true) {
            val header = line(); if (header.isEmpty()) break
            total += header.length; require(total <= 16384)
            if (header.substringBefore(':').equals("Range", true)) {
                require(range == null) { "Repeated range" }
                range = header.substringAfter(':').trim()
            }
        }
        val prefix = "/p/$token/"
        val path = request[1].substringBefore('?')
        val name = if (path.startsWith(prefix)) path.removePrefix(prefix) else ""
        val output = socket.getOutputStream()
        val cors = if (liveHls) "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Range, Content-Type\r\n" +
            "Access-Control-Allow-Private-Network: true\r\n" else ""
        if (liveHls && request[0] == "OPTIONS" && name.isNotEmpty()) {
            output.write("HTTP/1.1 204 No Content\r\n${cors}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val body = if (request[0] in setOf("GET", "HEAD") && name.matches(Regex("[a-zA-Z0-9.-]{1,80}")))
            resources.acquire(name) else null
        if (body == null) { output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
        try {
            val selected = RelayHttpRange.parse(range, body.size)
            if (selected == null) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${body.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val start = selected.start
            val end = selected.end
            val mime = when {
                name.endsWith(".mpd") -> "application/dash+xml"
                name.endsWith(".m3u8") -> "application/x-mpegURL"
                name.endsWith(".ts") -> "video/mp2t"
                else -> "video/mp4"
            }
            val length = selected.length
            val extra = if (range != null) "Content-Range: bytes $start-$end/${body.size}\r\n" else ""
            output.write(("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Type: $mime\r\n" +
                "Content-Length: $length\r\n${cors}Accept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray())
            if (request[0] == "GET") body.writeTo(output, start, length)
            output.flush()
        } finally { body.close() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close(); sockets.forEach { runCatching { it.close() } }; sockets.clear()
        accepting.shutdownNow(); workers.shutdownNow()
        resources.close()
    }
}
