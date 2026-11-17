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
internal class ComposedCmafRelay(address: String, private val peer: InetAddress) : AutoCloseable {
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0)
    val url = "http://$address:${server.localPort}/p/$token/manifest.mpd"
    private val accepting = Executors.newSingleThreadExecutor()
    private val workers = ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, ArrayBlockingQueue(6))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private var resources = emptyMap<String, ByteArray>()
    fun publish(next: ProtectedHevcComposedDashPackageBuilder.Package) = synchronized(lock) {
        require(!closed.get())
        require(next.resources.size <= 24 && next.resources.values.sumOf { it.size.toLong() } <= 128L * 1024 * 1024) { "Receiver media window exceeds its bounded memory budget" }
        val previous = resources; resources = next.resources
        previous.values.forEach { it.fill(0) }
    }
    init { accepting.execute {
        while (!closed.get()) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            if (socket.inetAddress != peer || sockets.size >= 9) { socket.close(); continue }
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
        val request = line().split(' '); require(request.size == 3)
        var total = 0; var range: String? = null
        while (true) {
            val header = line(); if (header.isEmpty()) break
            total += header.length; require(total <= 16384)
            if (header.substringBefore(':').equals("Range", true)) range = header.substringAfter(':').trim()
        }
        val prefix = "/p/$token/"
        val path = request[1].substringBefore('?')
        val name = if (path.startsWith(prefix)) path.removePrefix(prefix) else ""
        val body = if (request[0] in setOf("GET", "HEAD") && name.matches(Regex("[a-zA-Z0-9.-]{1,80}")))
            synchronized(lock) { resources[name]?.copyOf() } else null
        val output = socket.getOutputStream()
        if (body == null) { output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
        try {
            val parsed = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            val start = parsed?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val end = parsed?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (body.size - 1).toLong()
            if (range != null && (parsed == null || start !in 0L..body.lastIndex.toLong() || end < start || end >= body.size)) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            val mime = if (name.endsWith(".mpd")) "application/dash+xml" else "video/mp4"
            val length = end - start + 1
            val extra = if (range != null) "Content-Range: bytes $start-$end/${body.size}\r\n" else ""
            output.write(("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Type: $mime\r\n" +
                "Content-Length: $length\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray())
            if (request[0] == "GET") output.write(body, start.toInt(), length.toInt())
            output.flush()
        } finally { body.fill(0) }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close(); sockets.forEach { runCatching { it.close() } }; sockets.clear()
        accepting.shutdownNow(); workers.shutdownNow()
        synchronized(lock) { resources.values.forEach { it.fill(0) }; resources = emptyMap() }
    }
}
