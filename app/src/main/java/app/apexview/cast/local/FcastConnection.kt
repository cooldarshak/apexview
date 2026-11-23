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
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val outgoing = Channel<FcastProtocol.Packet>(32)
    // A framed FCast packet is at most 32KB. Buffering an entire packet
    // prevents five tiny TCP writes for its little-endian header/opcode when
    // tcpNoDelay is enabled, without delaying a single message's flush.
    private val ioBufferBytes = 32 * 1024
    private var reader: Job? = null
    private var writer: Job? = null

    fun start() {
        check(reader == null)
        socket.tcpNoDelay = true
        socket.soTimeout = 20_000
        writer = scope.launch(Dispatchers.IO) {
            try {
                BufferedOutputStream(socket.getOutputStream(), ioBufferBytes).use { output ->
                    for (packet in outgoing) FcastProtocol.write(output, packet)
                }
            } catch (error: Exception) { finish(error.javaClass.simpleName) }
        }
        reader = scope.launch(Dispatchers.IO) {
            try {
                BufferedInputStream(socket.getInputStream(), ioBufferBytes).use { input ->
                    while (!closed.get()) {
                        val packet = FcastProtocol.read(input) ?: break
                        // The owner still receives ordered packets; async timing
                        // parsing on Default never reorders geometry/table/frame.
                        kotlinx.coroutines.withContext(scope.coroutineContext) {
                            if (!closed.get()) onPacket(packet)
                        }
                    }
                }
                finish("Connection closed")
            } catch (error: Exception) { finish(error.javaClass.simpleName) }
        }
    }

    fun send(opcode: Int, body: JSONObject? = null) {
        if (!closed.get() && outgoing.trySend(FcastProtocol.Packet(opcode, body)).isFailure) {
            finish("Receiver control queue is full")
        }
    }
    private fun finish(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        outgoing.close()
        runCatching { socket.close() }
        reader?.cancel(); writer?.cancel()
        scope.launch { onClosed(reason) }
    }
    override fun close() = finish("Disconnected")
}
