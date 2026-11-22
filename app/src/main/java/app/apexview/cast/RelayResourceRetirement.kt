package app.apexview.cast

import java.net.InetAddress

/** Limit local Cast publication to the device interface advertised to its receiver. */
internal object CastRelayBinding {
    fun address(interfaceAddress: String): InetAddress {
        val address = InetAddress.getByName(interfaceAddress.removePrefix("[").removeSuffix("]"))
        require(!address.isAnyLocalAddress && !address.isMulticastAddress) {
            "A Pitwall Cast relay must bind a specific local interface"
        }
        return address
    }
}

/**
 * A rolling HLS/DASH update may reference the exact same immutable segment
 * buffer as the prior publication. Preserve retained buffers by identity;
 * securely zero every buffer not present in the next publication. The relay
 * always copies a served resource under its lock before writing to a socket.
 */
internal object RelayResourceRetirement {
    fun zeroRetired(previous: Collection<ByteArray>, next: Collection<ByteArray>) {
        previous.forEach { existing ->
            if (next.none { it === existing }) existing.fill(0)
        }
    }
}
