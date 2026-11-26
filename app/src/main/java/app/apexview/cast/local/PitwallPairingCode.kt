package app.apexview.cast.local

import java.security.MessageDigest

/** Compare the same ordered certificate identities on the two physical screens.
 * Discovery is not a trust root. A 96-bit code is never sent on the control link. */
internal object PitwallPairingCode {
    /** Public 96-bit fingerprint read from the physical TV screen, never from
     * discovery. It authenticates TV selection before any control messages.
     * The existing mutual SAS still authenticates the phone on the TV. */
    fun televisionIdentity(certificate: ByteArray): String {
        require(certificate.isNotEmpty())
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("Pitwall television selection v1".toByteArray(Charsets.US_ASCII))
        return digest.digest(certificate).take(12).joinToString("") { "%02X".format(it.toInt() and 255) }
            .chunked(4).joinToString(" ")
    }

    fun normalizeTelevisionIdentity(code: String): String? =
        code.filterNot { it == ' ' || it == '-' }.uppercase(java.util.Locale.ROOT)
            .takeIf { it.length == 24 && it.all { ch -> ch in '0'..'9' || ch in 'A'..'F' } }

    fun matchesTelevisionIdentity(expected: String, actual: String): Boolean {
        val a = normalizeTelevisionIdentity(expected) ?: return false
        val b = normalizeTelevisionIdentity(actual) ?: return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
    }

    fun fromCertificates(phone: ByteArray, television: ByteArray): String {
        require(phone.isNotEmpty() && television.isNotEmpty())
        val digest = MessageDigest.getInstance("SHA-256")
        val a = digest.digest(phone)
        val b = digest.digest(television)
        digest.update("Pitwall mutual TLS pairing v1".toByteArray(Charsets.US_ASCII))
        digest.update(a)
        digest.update(b)
        return digest.digest().take(12).joinToString("") { "%02X".format(it.toInt() and 255) }
            .chunked(4).joinToString(" ")
    }
}
