package app.apexview.cast.local

import java.security.MessageDigest

/** Compare the same ordered certificate identities on the two physical screens.
 * Discovery is not a trust root. A 96-bit code is never sent on the control link. */
internal object PitwallPairingCode {
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
