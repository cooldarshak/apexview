package app.apexview.cast

import org.junit.Assert.*
import org.junit.Test

class BakedCastSegmentQueueTest {
    private fun picture(us: Long, key: Boolean = false) =
        BakedCastTransportStream.Sample(us, byteArrayOf(1, 2, 3), true, key)
    private fun sound(us: Long) = BakedCastTransportStream.Sample(us, byteArrayOf(4, 5), false)

    @Test fun segmentWaitsForBothStreamsAndTransfersOnlyCompleteGop() {
        val queue = BakedCastSegmentQueue()
        queue.offerVideo(picture(0, true))
        queue.offerVideo(picture(40_000))
        queue.offerVideo(picture(1_000_000, true))
        queue.offerVideo(picture(2_000_000, true))
        queue.offerAudio(listOf(sound(0), sound(100_000)))
        assertNull(queue.takeReady())
        queue.offerAudio(listOf(sound(999_000), sound(1_000_000), sound(1_999_000), sound(2_000_000)))
        val first = requireNotNull(queue.takeReady())
        assertEquals(listOf(0L, 40_000L), first.video.map { it.ptsUs })
        assertEquals(listOf(0L, 100_000L, 999_000L), first.audio.map { it.ptsUs })
        assertEquals(1_000_000L, first.durationUs)
        val second = requireNotNull(queue.takeReady())
        assertEquals(1_000_000L, second.video.single().ptsUs)
        assertEquals(1, queue.videoCount)
        assertEquals(1, queue.audioCount)
        assertEquals(5L, queue.pendingBytes)
        assertNull(queue.takeReady())
        queue.clear()
        assertEquals(0L, queue.pendingBytes)
    }

    @Test fun stalledPublisherCannotAccumulateUnboundedEncodedMedia() {
        val queue = BakedCastSegmentQueue(maxVideo = 2, maxAudio = 2, maxBytes = 8)
        queue.offerVideo(picture(0, true))
        queue.offerVideo(picture(40_000))
        queue.offerAudio(listOf(sound(0)))
        assertTrue(runCatching { queue.offerVideo(picture(80_000)) }.isFailure)
        assertTrue(runCatching { queue.offerAudio(listOf(sound(100_000))) }.isFailure)
        assertEquals(8L, queue.pendingBytes)
    }
}
