package app.apexview.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayResourceRetirementTest {
    @Test fun retainedRollingSegmentSurvivesNewPlaylistPublication() {
        val active = byteArrayOf(12, 34, 56)
        val retired = byteArrayOf(8, 9, 10)
        val playlist = byteArrayOf(1, 2)
        RelayResourceRetirement.zeroRetired(listOf(active, retired, playlist), listOf(active))
        assertArrayEquals(byteArrayOf(12, 34, 56), active)
        assertArrayEquals(byteArrayOf(0, 0, 0), retired)
        assertArrayEquals(byteArrayOf(0, 0), playlist)
        RelayResourceRetirement.zeroRetired(listOf(active), emptyList())
        assertArrayEquals(byteArrayOf(0, 0, 0), active)
    }

    @Test fun equivalentButDistinctArraysAreNotMistakenForRetainedSecrets() {
        val previous = byteArrayOf(3, 4)
        val next = previous.copyOf()
        RelayResourceRetirement.zeroRetired(listOf(previous), listOf(next))
        assertArrayEquals(byteArrayOf(0, 0), previous)
        assertArrayEquals(byteArrayOf(3, 4), next)
    }

    @Test fun relayBindsTheSpecificAdvertisedIpInsteadOfEveryNetworkInterface() {
        assertEquals("127.0.0.1", CastRelayBinding.address("127.0.0.1").hostAddress)
        assertEquals("192.168.0.4", CastRelayBinding.address("192.168.0.4").hostAddress)
        assertThrows(IllegalArgumentException::class.java) {
            CastRelayBinding.address("0.0.0.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CastRelayBinding.address("224.0.0.1")
        }
    }
}
