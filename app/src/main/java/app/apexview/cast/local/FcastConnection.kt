package app.apexview.cast.local

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One reader and one bounded writer. No socket work or waits on the UI thread. */
internal class FcastConnection(
    private val scope: CoroutineScope,
    val socket: Socket,
    private val onPacket: suspend (FcastProtocol.Packet) -> Unit,
    private val onClosed: (String) -> Unit,
    val secureSession: PitwallTls.Session? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val connectionId = java.util.UUID.randomUUID().toString()
    private val startedNs = System.nanoTime()
    private val received = java.util.concurrent.atomic.AtomicLong()
    private val sent = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var lastRead = -1
    @Volatile private var lastWrite = -1
    @Volatile private var handshakeDone = false
    private val outgoing = Channel<FcastProtocol.Packet>(32)
    // A framed FCast packet is at most 32KB. Buffering an entire packet
    // prevents five tiny TCP writes for its little-endian header/opcode when
    // tcpNoDelay is enabled, without delaying a single message's flush.
    private val ioBufferBytes = 32 * 1024
    private var reader: Job? = null
    private var writer: Job? = null

    fun start(handshakeTimeoutMs: Int = 20_000) {
        check(reader == null)
        socket.tcpNoDelay = true
        require(handshakeTimeoutMs in 20_000..100_000)
        socket.soTimeout = handshakeTimeoutMs
        writer = scope.launch(Dispatchers.IO) {
            try {
                BufferedOutputStream(socket.getOutputStream(), ioBufferBytes).use { output ->
                    for (packet in outgoing) {
                        FcastProtocol.write(output, packet)
                        lastWrite = packet.opcode
                        sent.incrementAndGet()
                    }
                }
            } catch (error: Exception) { finish("WRITE_${error.javaClass.simpleName}") }
        }
        reader = scope.launch(Dispatchers.IO) {
            try {
                BufferedInputStream(socket.getInputStream(), ioBufferBytes).use { input ->
                    while (!closed.get()) {
                        val packet = FcastProtocol.read(input) ?: break
                        lastRead = packet.opcode
                        received.incrementAndGet()
                        // The owner still receives ordered packets; async timing
                        // parsing on Default never reorders geometry/table/frame.
                        kotlinx.coroutines.withContext(scope.coroutineContext.minusKey(Job)) {
                            if (!closed.get()) onPacket(packet)
                        }
                    }
                }
                finish("PEER_EOF")
            } catch (error: Exception) { finish("READ_${error.javaClass.simpleName}") }
        }
    }

    fun handshakeComplete() {
        if (!closed.get()) {
            socket.soTimeout = 20_000
            handshakeDone = true
        }
    }

    fun send(opcode: Int, body: JSONObject? = null) {
        if (!closed.get() && outgoing.trySend(FcastProtocol.Packet(opcode, body)).isFailure) {
            finish("CONTROL_QUEUE_FULL")
        }
    }
    private fun finish(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { android.util.Log.i("PitwallCast", "CAST_CONTROL_CLOSE id=$connectionId reason=$reason " +
            "handshake=$handshakeDone elapsedMs=${(System.nanoTime()-startedNs)/1_000_000} " +
            "sent=${sent.get()} received=${received.get()} lastRead=$lastRead lastWrite=$lastWrite") }
        outgoing.close()
        runCatching { socket.close() }
        reader?.cancel(); writer?.cancel()
        scope.launch { onClosed(reason) }
    }
    override fun close() = finish("LOCAL_CLOSE")
}
