package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ClearCompositionCertificatesTest {
    private fun request(media: ByteArray = byteArrayOf(1,2,3)) =
        HevcClearCmafGridComposer.Request(
            listOf(HevcClearCmafGridComposer.Feed(byteArrayOf(9), listOf(media), "main"),
                HevcClearCmafGridComposer.Feed(byteArrayOf(8), listOf(byteArrayOf(4)), "onboard")),
            HevcOutputGridDimensions(2,1),
            listOf(HevcTizenGridComposer.CellSelection(0,0,0,0), HevcTizenGridComposer.CellSelection(1,0,1,0)))

    @Test fun onlyExactBytesAndAllRequestFieldsReuseAProof() {
        val key = ClearCompositionCertificates.key(request())
        assertEquals(key, ClearCompositionCertificates.key(request()))
        assertNotEquals(key, ClearCompositionCertificates.key(request(byteArrayOf(1,2,4))))
        assertNotEquals(key, ClearCompositionCertificates.key(request().copy(firstOutputSequenceNumber=2)))
        assertNotEquals(key, ClearCompositionCertificates.key(request().copy(frameRateNumerator=25)))
        assertNotEquals(key, ClearCompositionCertificates.key(request().copy(cells=request().cells.reversed())))
        assertNotEquals(key, ClearCompositionCertificates.key(request().copy(feeds=request().feeds.reversed())))
        val cache = ClearCompositionCertificates()
        val init = byteArrayOf(1,2,3)
        val fragment = byteArrayOf(4,5,6,7)
        cache.remember(key, init, listOf(fragment))
        val proof = cache.find(key)!!
        assertEquals(15, proof.requiredBytes)
        init.fill(0); fragment.fill(0)
        assertArrayEquals(byteArrayOf(1,2,3),proof.initialization())
        assertEquals(listOf(ClearCompositionCertificates.digest(byteArrayOf(4,5,6,7))), proof.mediaDigests)
        assertNull(cache.find(ClearCompositionCertificates.key(request(byteArrayOf(1,2,4)))))
        repeat(4) { cache.remember("other-$it",byteArrayOf(1),listOf(byteArrayOf(2))) }
        assertNull(cache.find(key))
        cache.clear()
        assertNull(cache.find("other-3"))
    }

    @Test fun directViewDigestDoesNotChangeCursorOrCopyTheBacking() {
        val bytes = byteArrayOf(4,5,6,7)
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        buffer.position(2)
        assertEquals(ClearCompositionCertificates.digest(bytes), ClearCompositionCertificates.digest(buffer))
        assertEquals(2,buffer.position())
    }
}
