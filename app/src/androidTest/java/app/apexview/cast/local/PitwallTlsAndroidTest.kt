package app.apexview.cast.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.security.auth.x500.X500Principal
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PitwallTlsAndroidTest {
    @Test fun legacyKeyMigratesOnceAndNewKeySurvivesReopen() {
        val alias = "pitwall_tls_test_" + UUID.randomUUID()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=Pitwall LAN"))
                    .build())
                generateKeyPair()
            }
            val legacy = store.getCertificate(alias).encoded
            assertFalse(PitwallTls.supportsTlsSigning(store.getKey(alias, null) as PrivateKey))
            val migrated = PitwallTls.identityForAlias(alias).certificate.encoded
            assertFalse(legacy.contentEquals(migrated))
            assertTrue(PitwallTls.supportsTlsSigning(store.getKey(alias, null) as PrivateKey))
            assertArrayEquals(migrated, PitwallTls.identityForAlias(alias).certificate.encoded)
            assertNull("Private installation keys must remain non-exportable", store.getKey(alias, null).encoded)
        } finally { store.deleteEntry(alias) }
    }

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
                    val sender = try {
                        PitwallTls.connect(phone, address, server.localPort, arrayOf(version))
                    } catch (failure: Exception) {
                        // Preserve the server-side exception instead of hiding it in an unread Future.
                        runCatching { accepted.get(10, TimeUnit.SECONDS) }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                        throw AssertionError("Mutual control handshake failed for $version", failure)
                    }
                    val receiver = accepted.get(12, TimeUnit.SECONDS)
                    sender.socket.use { assertEquals(version, it.session.protocol) }
                    receiver.socket.use { assertEquals(version, it.session.protocol) }
                    assertEquals(sender.session.code, receiver.session.code)
                    sender.session to receiver.session
                }
                PitwallTls.controlServer(tv, 0, arrayOf(version)).use { server ->
                    val accepted = worker.submit<PitwallTls.Provisional> { PitwallTls.accept(tv, server.accept()) }
                    PitwallTls.connect(phone, address, server.localPort).socket.use { socket ->
                        assertEquals(version, socket.session.protocol)
                        accepted.get(12, TimeUnit.SECONDS).socket.use {
                            assertEquals(version, it.session.protocol)
                        }
                    }
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
