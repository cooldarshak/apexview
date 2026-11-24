package app.apexview.cast.local

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class ReceiverMediaPolicyTest {
    private val phone = InetAddress.getByName("192.168.1.10")
    @Test fun permitsPhoneRelayButNotOtherLanHostsDnsCredentialsOrNonHttpSchemes() {
        assertEquals("192.168.1.10", ReceiverMediaPolicy.validate("http://192.168.1.10:40000/s/random/master.m3u8", phone).host)
        for (url in listOf("http://192.168.1.11/private", "http://127.0.0.1/internal", "http://phone.local/video", "file:///sdcard/private", "http://user:secret@192.168.1.10/", "http://192.168.1.10:99999/", "ftp://192.168.1.10/video")) {
            assertThrows(url, IllegalArgumentException::class.java) { ReceiverMediaPolicy.validate(url, phone) }
        }
        assertThrows(IllegalArgumentException::class.java) { ReceiverMediaPolicy.validate("http://8.8.8.8/video", InetAddress.getByName("8.8.8.8")) }
    }
    @Test fun pitwallResourcesCannotLeaveTheNegotiatedCapabilityDirectory() {
        val prefix = "http://192.168.1.10:40000/s/" + "a".repeat(48) + "/"
        val origin = ReceiverMediaPolicy.validate(prefix + "master.m3u8", phone)
        assertEquals("s-1.m4s", ReceiverMediaPolicy.validateResource(prefix + "s-1.m4s", phone, origin, true).path.substringAfterLast('/'))
        for (url in listOf(prefix + "../secret", prefix + "%2e%2e/secret", prefix + "child/file",
            prefix.replace("a".repeat(48), "b".repeat(48)) + "s-1.m4s",
            prefix.replace(":40000", ":40001") + "s-1.m4s", prefix + ".")) {
            assertThrows(url, IllegalArgumentException::class.java) {
                ReceiverMediaPolicy.validateResource(url, phone, origin, true)
            }
        }
        assertEquals("/legacy", ReceiverMediaPolicy.validateResource("http://192.168.1.10:40000/legacy", phone, origin, false).path)
    }
}
