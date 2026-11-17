package app.apexview.cast.local

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FcastProtocolTest {
    @Test fun readsPublishedLittleEndianSeekAndBodylessPauseEvenWhenTcpSplitsEveryByte() {
        val bytes = byteArrayOf(13, 0, 0, 0, 5) + "{\"time\":120}".toByteArray() + byteArrayOf(1, 0, 0, 0, 2)
        val fragmented = object : InputStream() {
            var cursor = 0
            override fun read(): Int = if (cursor == bytes.size) -1 else bytes[cursor++].toInt() and 255
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (cursor == bytes.size) return -1
                b[off] = bytes[cursor++]; return 1
            }
        }
        val seek = FcastProtocol.read(fragmented)!!
        assertEquals(FcastProtocol.SEEK, seek.opcode)
        assertEquals(120, seek.body!!.getInt("time"))
        assertEquals(FcastProtocol.PAUSE, FcastProtocol.read(fragmented)!!.opcode)
        assertNull(FcastProtocol.read(fragmented))
    }
    @Test fun rejectsHugeUnsignedLengthsAndTruncatedFramesBeforeAllocatingBody() {
        for (bytes in listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(-1, -1, -1, -1), byteArrayOf(1, -128, 0, 0))) {
            assertThrows(IllegalArgumentException::class.java) { FcastProtocol.read(ByteArrayInputStream(bytes)) }
        }
        assertThrows(EOFException::class.java) { FcastProtocol.read(ByteArrayInputStream(byteArrayOf(1, 0))) }
        assertThrows(EOFException::class.java) { FcastProtocol.read(ByteArrayInputStream(byteArrayOf(1, 0, 0, 0))) }
        assertThrows(EOFException::class.java) { FcastProtocol.read(ByteArrayInputStream(byteArrayOf(3, 0, 0, 0, 1, 123))) }
        assertThrows(IllegalArgumentException::class.java) {
            FcastProtocol.write(ByteArrayOutputStream(), FcastProtocol.Packet(1, JSONObject().put("content", "x".repeat(32000))))
        }
    }
    @Test fun controlsAndLayoutTravelInOrderOverARealSocketAndClosingReleasesReader() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val messages = LinkedBlockingQueue<FcastProtocol.Packet>()
        val closed = LinkedBlockingQueue<String>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { phone ->
                val tv = server.accept()
                val connection = FcastConnection(scope, tv, messages::add, closed::add)
                connection.start()
                try {
                    FcastProtocol.write(phone.getOutputStream(), FcastProtocol.Packet(FcastProtocol.VERSION, JSONObject().put("version", 3)))
                    FcastProtocol.write(phone.getOutputStream(), FcastProtocol.Packet(FcastProtocol.INITIAL, JSONObject().put("pitwallScene", 1)))
                    FcastProtocol.write(phone.getOutputStream(), FcastProtocol.Packet(FcastProtocol.PAUSE))
                    assertEquals(11, messages.poll(3, TimeUnit.SECONDS)?.opcode)
                    assertEquals(14, messages.poll(3, TimeUnit.SECONDS)?.opcode)
                    assertEquals(2, messages.poll(3, TimeUnit.SECONDS)?.opcode)
                    connection.send(FcastProtocol.RENDERING, JSONObject().put("sources", 4).put("playbackId", "new-stream"))
                    phone.soTimeout = 3000
                    val ack = FcastProtocol.read(phone.getInputStream())!!
                    assertEquals(129, ack.opcode)
                    assertEquals("new-stream", ack.body!!.getString("playbackId"))
                    connection.close()
                    assertNotNull(closed.poll(3, TimeUnit.SECONDS))
                    assertTrue(tv.isClosed)
                } finally { connection.close(); scope.cancel() }
            }
        }
    }
}
