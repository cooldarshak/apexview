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
        assertEquals(first.bytes + second.bytes,queue.publishingBytes)
        queue.clear()
        assertEquals(0L, queue.pendingBytes)
        assertEquals(first.bytes + second.bytes,queue.bytesInFlight)
        queue.finish(first); queue.finish(second); queue.finish(first)
        assertEquals(0L,queue.bytesInFlight)
    }

    @Test fun retirementErasesPendingButNotPublishingSamples() {
        val queue = BakedCastSegmentQueue()
        val discarded = picture(0)
        val first = picture(100, true)
        val pending = picture(1_000_100, true)
        val audio = sound(100)
        val nextAudio = sound(1_000_100)
        queue.offerVideo(discarded)
        assertTrue(discarded.bytes.all { it == 0.toByte() })
        queue.offerVideo(first); queue.offerVideo(pending)
        queue.offerAudio(listOf(audio, nextAudio))
        val ready = requireNotNull(queue.takeReady())
        queue.clear()
        assertTrue(pending.bytes.all { it == 0.toByte() })
        assertTrue(nextAudio.bytes.all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(1, 2, 3), first.bytes)
        assertArrayEquals(byteArrayOf(4, 5), audio.bytes)
        assertEquals(ready.bytes, queue.bytesInFlight)
        queue.finish(ready)
        assertEquals(0L, queue.bytesInFlight)
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

    @Test fun dequeuedPublisherStillCountsAgainstByteLimit() {
        val queue = BakedCastSegmentQueue(maxBytes = 10)
        queue.offerVideo(picture(0,true))
        queue.offerVideo(picture(1_000_000,true))
        queue.offerAudio(listOf(sound(0),sound(1_000_000)))
        val ready = requireNotNull(queue.takeReady())
        assertEquals(10L,queue.bytesInFlight)
        assertTrue(runCatching { queue.offerAudio(listOf(sound(1_100_000))) }.isFailure)
        queue.finish(ready)
        queue.offerAudio(listOf(sound(1_100_000)))
        assertEquals(7L,queue.bytesInFlight)
        queue.clear()
    }

    @Test fun invalidBatchNeverPartiallyEnqueuesAudio() {
        val queue = BakedCastSegmentQueue(maxAudio = 4, maxBytes = 8)
        queue.offerAudio(listOf(sound(0)))
        assertTrue(runCatching { queue.offerAudio(listOf(sound(10), sound(10))) }.isFailure)
        assertEquals(1, queue.audioCount)
        assertEquals(0L, queue.latestAudioUs)
        assertEquals(2L, queue.pendingBytes)
        queue.offerAudio(listOf(sound(10), sound(20), sound(30)))
        assertEquals(8L, queue.pendingBytes)
        queue.clear()
        assertEquals(0L, queue.bytesInFlight)
    }
}
