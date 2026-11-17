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
}
