package app.apexview.cast.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/** Real loopback TLS handshake with ephemeral software test identities.
 * Android production generates non-exportable installation keys instead. */
class PitwallTlsTest {
    private fun identity(dir: File, name: String): PitwallTls.Identity {
        val file = File(dir, "$name.p12")
        val password = "test-only-password"
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        val process = ProcessBuilder(keytool.absolutePath, "-genkeypair", "-alias", name,
            "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=$name",
            "-validity", "2", "-storetype", "PKCS12", "-keystore", file.absolutePath,
            "-storepass", password, "-keypass", password, "-noprompt")
            .redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        val store = KeyStore.getInstance("PKCS12")
        file.inputStream().use { store.load(it, password.toCharArray()) }
        return PitwallTls.Identity(store.getKey(name, password.toCharArray()) as PrivateKey,
            store.getCertificate(name) as X509Certificate)
    }

    @Test fun rememberedTrustBindsBothInstallationCertificatesAndRole() {
        val phone=byteArrayOf(1,2,3)
        val tv=byteArrayOf(4,5,6)
        val key=PitwallRememberedPeers.key(phone,tv)
        assertEquals(key,PitwallRememberedPeers.key(phone.copyOf(),tv.copyOf()))
        assertNotEquals(key,PitwallRememberedPeers.key(tv,phone))
        assertNotEquals(key,PitwallRememberedPeers.key(phone,byteArrayOf(4,5,7)))
        assertNotEquals(key,PitwallRememberedPeers.key(byteArrayOf(1,2,4),tv))
        assertEquals(64,key.length)
        val keys=(0..40).map { PitwallRememberedPeers.key(phone,byteArrayOf(it.toByte())) }
        val bounded=keys.fold(emptySet<String>()) { old,next -> PitwallRememberedPeers.add(old,next) }
        assertEquals(32,bounded.size)
        assertTrue(keys.last() in bounded)
    }

    @Test fun handshakeDiagnosticsContainTypesNotSecretExceptionText() {
        val failure = javax.net.ssl.SSLHandshakeException("private peer details").apply {
            initCause(java.security.cert.CertificateException("secret certificate"))
        }
        assertEquals("SSLHandshakeException>CertificateException", PitwallTls.failureClasses(failure))
    }

    @Test fun bothControlPeersSeeSameCodeAndOnlyPairedClientReadsMedia() {
        val dir = Files.createTempDirectory("pitwall-tls-test").toFile()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val phone = identity(dir, "phone")
            val tv = identity(dir, "tv")
            val stranger = identity(dir, "stranger")
            assertThrows(java.security.cert.CertificateException::class.java) {
                PitwallTls.PeerTrust(tv.certificate).checkServerTrusted(arrayOf(stranger.certificate), "EC")
            }
            PitwallTls.PeerTrust(tv.certificate).checkServerTrusted(arrayOf(tv.certificate), "EC")
            val loopback = InetAddress.getLoopbackAddress()
            val pair = PitwallTls.controlServer(tv, 0).use { server ->
                val accepted = executor.submit<PitwallTls.Provisional> {
                    PitwallTls.accept(tv, server.accept())
                }
                val sender = PitwallTls.connect(phone, loopback, server.localPort)
                val receiver = accepted.get(12, TimeUnit.SECONDS)
                assertEquals(sender.session.code, receiver.session.code)
                sender.socket.close(); receiver.socket.close()
                sender.session to receiver.session
            }
            pair.first.mediaServer(loopback).use { server ->
                val read = executor.submit<Int> {
                    server.accept().use { socket ->
                        socket.soTimeout = 8_000
                        (socket as SSLSocket).startHandshake()
                        socket.getInputStream().read()
                    }
                }
                pair.second.socketFactory.createSocket(loopback, server.localPort).use { socket ->
                    socket.soTimeout = 8_000
                    (socket as SSLSocket).startHandshake()
                    socket.getOutputStream().write(73); socket.getOutputStream().flush()
                    assertEquals(73, read.get(10, TimeUnit.SECONDS))
                }
                val reject = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 8_000
                        runCatching { (socket as SSLSocket).startHandshake() }.isFailure
                    }
                }
                val wrong = PitwallTls.Session(stranger, phone.certificate, "test")
                runCatching {
                    wrong.socketFactory.createSocket(loopback, server.localPort).use { socket ->
                        socket.soTimeout = 8_000
                        (socket as SSLSocket).startHandshake()
                        socket.getOutputStream().write(1); socket.getOutputStream().flush()
                        socket.getInputStream().read()
                    }
                }
                assertTrue(reject.get(10, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }
}
