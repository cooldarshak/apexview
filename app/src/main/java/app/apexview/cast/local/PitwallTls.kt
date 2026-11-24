package app.apexview.cast.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Pitwall private LAN transport. Non-exportable installation identity, TLS 1.2+
 * mutual certificate proof, explicit comparison on both screens before sending
 * playback commands, then exact certificate pinning on each media connection.
 * No trust in mDNS, no HTTP fallback for a Pitwall receiver, no F1 credentials.
 */
internal object PitwallTls {
    const val SERVICE_TYPE = "_pitwall._tcp."
    private const val ALIAS = "pitwall_lan_identity_v1"
    private const val HANDSHAKE_TIMEOUT_MS = 8_000

    class Identity internal constructor(private val key: PrivateKey, val certificate: X509Certificate) {
        private fun manager() = object : X509ExtendedKeyManager() {
            private fun supported(type: String?) = type?.startsWith("EC", ignoreCase = true) == true
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (supported(keyType)) arrayOf(ALIAS) else null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
                if (keyType?.any(::supported) == true) ALIAS else null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (supported(keyType)) arrayOf(ALIAS) else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
                if (supported(keyType)) ALIAS else null
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?): String? =
                if (keyType?.any(::supported) == true) ALIAS else null
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?): String? =
                if (supported(keyType)) ALIAS else null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == ALIAS) arrayOf(certificate) else null
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null
        }
        internal fun context(trust: X509TrustManager): SSLContext = SSLContext.getInstance("TLS").apply {
            init(arrayOf(manager()), arrayOf(trust), SecureRandom())
        }
    }

    @Synchronized fun identity(): Identity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getCertificate(ALIAS) as? X509Certificate
        if (existing == null || runCatching { existing.checkValidity() }.isFailure) {
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=Pitwall LAN"))
                .setCertificateSerialNumber(java.math.BigInteger(128, SecureRandom()).add(java.math.BigInteger.ONE))
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + 10L * 365 * 86_400_000L))
                .build())
            generator.generateKeyPair()
        }
        val cert = requireNotNull(store.getCertificate(ALIAS) as? X509Certificate)
        val key = requireNotNull(store.getKey(ALIAS, null) as? PrivateKey)
        return Identity(key, cert)
    }

    /** Unpinned certificates are allowed ONLY for the provisional control handshake.
     * TLS verifies private-key possession; physical comparison is the trust decision. */
    internal class PeerTrust(private val pinned: X509Certificate? = null) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = pinned?.let { arrayOf(it) } ?: emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)
        private fun verify(chain: Array<out X509Certificate>?) {
            val cert = chain?.singleOrNull() ?: throw CertificateException("Expected one Pitwall identity certificate")
            try {
                cert.checkValidity()
                if (cert.publicKey.algorithm != "EC") throw CertificateException("Expected EC identity")
                cert.verify(cert.publicKey)
                if (pinned != null && !MessageDigest.isEqual(pinned.encoded, cert.encoded))
                    throw CertificateException("Pitwall peer identity changed")
            } catch (failure: CertificateException) { throw failure }
            catch (failure: Exception) { throw CertificateException("Invalid Pitwall peer identity", failure) }
        }
    }

    class Session internal constructor(private val identity: Identity, private val peer: X509Certificate,
                                       val code: String) {
        val trustManager: X509TrustManager = PeerTrust(peer)
        private val pinnedContext by lazy { identity.context(trustManager) }
        val socketFactory get() = pinnedContext.socketFactory
        fun mediaServer(address: InetAddress): ServerSocket =
            server(pinnedContext, 0, 16, address)
    }

    data class Provisional(val socket: SSLSocket, val session: Session)
    private fun protocols(supported: Array<String>): Array<String> =
        supported.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray().also { require(it.isNotEmpty()) }
    private fun server(context: SSLContext, port: Int, backlog: Int, address: InetAddress?): SSLServerSocket =
        (context.serverSocketFactory.createServerSocket(port, backlog, address) as SSLServerSocket).apply {
            enabledProtocols = protocols(supportedProtocols)
            needClientAuth = true
        }
    fun controlServer(identity: Identity, port: Int): ServerSocket =
        server(identity.context(PeerTrust()), port, 2, null)

    fun connect(identity: Identity, address: InetAddress, port: Int): Provisional {
        val socket = identity.context(PeerTrust()).socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(address, port), 6_000)
            return handshake(identity, socket, true)
        } catch (failure: Throwable) { runCatching { socket.close() }; throw failure }
    }

    fun accept(identity: Identity, socket: Socket): Provisional =
        try { handshake(identity, socket as SSLSocket, false) }
        catch (failure: Throwable) { runCatching { socket.close() }; throw failure }

    internal fun failureClasses(failure: Throwable): String =
        generateSequence(failure) { current -> current.cause?.takeUnless { it === current } }
            .take(6).joinToString(">") { it.javaClass.simpleName }

    private fun handshake(identity: Identity, socket: SSLSocket, phone: Boolean): Provisional {
        socket.useClientMode = phone
        socket.enabledProtocols = protocols(socket.supportedProtocols)
        if (!phone) socket.needClientAuth = true
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        try {
            socket.startHandshake()
        } catch (failure: Exception) {
            // No peer addresses, certificates, pairing codes or raw exception text.
            runCatching { android.util.Log.w("PitwallTls",
                "TLS_HANDSHAKE_FAILURE role=${if (phone) "client" else "server"} protocols=${socket.enabledProtocols.joinToString()} causes=${failureClasses(failure)}") }
            throw failure
        }
        runCatching { android.util.Log.i("PitwallTls",
            "TLS_HANDSHAKE_OK role=${if (phone) "client" else "server"} protocol=${socket.session.protocol} cipher=${socket.session.cipherSuite}") }
        val peer = socket.session.peerCertificates.singleOrNull() as? X509Certificate
            ?: throw CertificateException("Pitwall peer certificate missing")
        val code = if (phone) PitwallPairingCode.fromCertificates(identity.certificate.encoded, peer.encoded)
            else PitwallPairingCode.fromCertificates(peer.encoded, identity.certificate.encoded)
        return Provisional(socket, Session(identity, peer, code))
    }
}
