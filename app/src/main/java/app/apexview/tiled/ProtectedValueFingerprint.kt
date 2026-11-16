package app.apexview.tiled

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Process-local keyed identity for clear CENC metadata values.
 * Only the HMAC digest is retained. The random key is neither persisted nor exposed.
 */
internal class ProtectedValueFingerprint private constructor(private val digest: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is ProtectedValueFingerprint && MessageDigest.isEqual(digest, other.digest)

    override fun hashCode(): Int = digest.contentHashCode()

    override fun toString(): String = "ProtectedValueFingerprint(<redacted>)"

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private val processSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        private val macInstances = object : ThreadLocal<Mac>() {
            override fun initialValue(): Mac =
                Mac.getInstance(ALGORITHM).apply {
                    init(SecretKeySpec(processSecret, ALGORITHM))
                }
        }

        fun fromBytes(domain: String, value: ByteArray): ProtectedValueFingerprint {
            require(domain.isNotBlank())
            val mac = macInstances.get()
            mac.reset()
            mac.update(domain.toByteArray(Charsets.UTF_8))
            mac.update(0.toByte())
            return ProtectedValueFingerprint(mac.doFinal(value))
        }
    }
}
