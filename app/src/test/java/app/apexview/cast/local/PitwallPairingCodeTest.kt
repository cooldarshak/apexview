package app.apexview.cast.local

import org.junit.Assert.*
import org.junit.Test

class PitwallPairingCodeTest {
    @Test fun orderedIdentityComparisonChangesWithEitherPeer() {
        val phone = byteArrayOf(1, 2, 3)
        val tv = byteArrayOf(4, 5, 6)
        val code = PitwallPairingCode.fromCertificates(phone, tv)
        assertTrue(code.matches(Regex("[0-9A-F]{4}( [0-9A-F]{4}){5}")))
        assertEquals(code, PitwallPairingCode.fromCertificates(phone.copyOf(), tv.copyOf()))
        assertNotEquals(code, PitwallPairingCode.fromCertificates(tv, phone))
        assertNotEquals(code, PitwallPairingCode.fromCertificates(byteArrayOf(1, 2, 4), tv))
        assertNotEquals(code, PitwallPairingCode.fromCertificates(phone, byteArrayOf(4, 5, 7)))
        assertThrows(IllegalArgumentException::class.java) {
            PitwallPairingCode.fromCertificates(byteArrayOf(), tv)
        }
    }
}
