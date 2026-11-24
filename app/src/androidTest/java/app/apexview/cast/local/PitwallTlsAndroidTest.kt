package app.apexview.cast.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PitwallTlsAndroidTest {
    @Test fun realAndroidKeyStoreMutualTlsAndPinnedMediaOnBothVersions() {
        val prefix = "pitwall_tls_test_" + UUID.randomUUID()
        val aliases = listOf(prefix + "_phone", prefix + "_tv", prefix + "_wrong")
        val worker = Executors.newSingleThreadExecutor()
        val address = InetAddress.getLoopbackAddress()
        try {
            val (phone, tv, wrong) = aliases.map(PitwallTls::identityForAlias)
            for (version in listOf("TLSv1.2", "TLSv1.3")) {
                val pair = PitwallTls.controlServer(tv, 0, arrayOf(version)).use { server ->
                    val accepted = worker.submit<PitwallTls.Provisional> { PitwallTls.accept(tv, server.accept()) }
                    val sender = PitwallTls.connect(phone, address, server.localPort, arrayOf(version))
                    val receiver = accepted.get(12, TimeUnit.SECONDS)
                    sender.socket.use { assertEquals(version, it.session.protocol) }
                    receiver.socket.use { assertEquals(version, it.session.protocol) }
                    assertEquals(sender.session.code, receiver.session.code)
                    sender.session to receiver.session
                }
                pair.first.mediaServer(address).use { server ->
                    val accepted = worker.submit<Int> {
                        (server.accept() as SSLSocket).use {
                            it.soTimeout = 8000
                            it.startHandshake()
                            assertEquals(version, it.session.protocol)
                            it.inputStream.read()
                        }
                    }
                    (pair.second.socketFactory.createSocket(address, server.localPort) as SSLSocket).use {
                        it.soTimeout = 8000
                        it.startHandshake()
                        it.outputStream.write(73)
                        it.outputStream.flush()
                    }
                    assertEquals(73, accepted.get(12, TimeUnit.SECONDS))
                    val rejected = worker.submit<Boolean> {
                        (server.accept() as SSLSocket).use {
                            it.soTimeout = 8000
                            runCatching { it.startHandshake() }.isFailure
                        }
                    }
                    val wrongSession = PitwallTls.Session(wrong, phone.certificate, "test")
                    runCatching {
                        (wrongSession.socketFactory.createSocket(address, server.localPort) as SSLSocket).use {
                            it.soTimeout = 8000
                            it.startHandshake()
                            it.inputStream.read()
                        }
                    }
                    assertTrue(rejected.get(12, TimeUnit.SECONDS))
                }
            }
        } finally {
            worker.shutdownNow()
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            aliases.forEach { store.deleteEntry(it) }
        }
    }
}
