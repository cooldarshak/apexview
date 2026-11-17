package app.apexview.cast.local

import java.net.InetAddress
import java.net.URI

/** The receiver is a LAN player, never an arbitrary URL/header proxy or a license relay. */
internal object ReceiverMediaPolicy {
    fun validate(url: String, peer: InetAddress): URI {
        val uri = URI(url)
        require(uri.scheme == "http" || uri.scheme == "https") { "Only HTTP media is supported" }
        require(uri.userInfo == null && uri.fragment == null && uri.host != null) { "Invalid media URL" }
        // Numeric peer address only. No DNS lookup / rebinding, loopback or another LAN machine.
        require(uri.host.trim('[', ']').equals(peer.hostAddress?.substringBefore('%'), true)) {
            "Media must come directly from the connected phone"
        }
        require(peer.isSiteLocalAddress || peer.isLinkLocalAddress) { "Use a local Wi-Fi or Ethernet connection" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid media port" }
        return uri
    }
}
