package app.apexview.cast

import android.net.Uri
import android.util.Log
import app.apexview.tiled.CmafHttpReader
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One continuously captured, baked phone picture + original authorized AAC commentary. */
internal class BakedCastLiveSession(
    address: String, peer: InetAddress?, reader: CmafHttpReader, audioUri: Uri,
    val programmeLabel: String, val feeds: Int, scope: CoroutineScope,
    tls: app.apexview.cast.local.PitwallTls.Session? = null,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val hls = BakedCastHlsSession(address, peer, tls)
    private val audioReader = reader
    private val audioSource = audioUri
    private var audio: BakedCastArchiveAudio? = null
    private var sourceStartMs = -1L
    private val firstSegment = CompletableDeferred<Unit>()
    private val video = ArrayDeque<BakedCastTransportStream.Sample>()
    private val commentary = ArrayDeque<BakedCastTransportStream.Sample>()
    private var stopped = false
    private var published = 0L
    private var publishedDurationUs = 0L
    private var lastPublicationNs = 0L
    @Volatile var receiverRendering = false
    @Volatile var previewSuppressionRequested = false
    @Synchronized fun health(): String = "segments=$published mediaUs=$publishedDurationUs windowUs=${hls.advertisedDurationUs} videoUs=$latestVideoUs audioUs=$latestAudioUs queuedVideo=${video.size} queuedAudio=${commentary.size}"
    private var latestVideoUs = 0L
    private var latestAudioUs = 0L
    private var lastVideoUs = -1L
    var width = 0
        private set
    var height = 0
        private set
    val url get() = hls.url
    val playable get() = firstSegment.isCompleted && !firstSegment.isCancelled

    private val audioJob: Job = scope.launch(Dispatchers.IO) {
        try {
            while (true) {
                val request = synchronized(this@BakedCastLiveSession) {
                    if (stopped) return@launch
                    audio?.let { it to (sourceStartMs * 1000 + latestVideoUs + 5_000_000) }
                }
                if (request == null) { delay(50); continue }
                // Fetch a short distance ahead of the captured picture, not the entire replay.
                val samples = request.first.readUntil(request.second)
                synchronized(this@BakedCastLiveSession) {
                    if (stopped) return@launch
                    samples.forEach(commentary::addLast)
                    samples.lastOrNull()?.let { latestAudioUs = it.ptsUs }
                    publishReadySegments()
                }
                delay(800)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail("Clear archive commentary: ${error.message ?: error.javaClass.simpleName}") }
    }

    @Synchronized fun setOutputSize(w: Int, h: Int, playbackPositionMs: Long) {
        require(w > 0 && h > 0 && playbackPositionMs >= 0)
        check(!stopped && audio == null) { "Clear Cast output already anchored or closed" }
        val sourceStartUs = Math.multiplyExact(playbackPositionMs, 1_000L)
        width = w; height = h
        sourceStartMs = playbackPositionMs
        audio = BakedCastArchiveAudio(audioReader, audioSource, sourceStartUs)
        Log.i("PitwallCast", "BAKED_CAST_SOURCE_CLOCK startMs=$sourceStartMs encoderPicture=${width}x$height")
    }

    /** Called on the GL encoder thread. Nothing here may block on network I/O. */
    @Synchronized fun videoSample(sample: BakedCastTransportStream.Sample) {
        if (stopped) return
        try {
            require(sample.video && (lastVideoUs < 0 || sample.ptsUs > lastVideoUs)) {
                "The scene encoder did not produce monotonically increasing timestamps"
            }
            if (sample.keyFrame || video.isNotEmpty()) video.addLast(sample)
            lastVideoUs = sample.ptsUs
            latestVideoUs = sample.ptsUs
            require(video.size < 350) { "Live commentary cannot keep up with the video encoder" }
            publishReadySegments()
        } catch (error: Exception) { fail("Scene Cast encoder: ${error.message ?: error.javaClass.simpleName}") }
    }

    @Synchronized private fun publishReadySegments() {
        while (!stopped && video.isNotEmpty() && commentary.isNotEmpty()) {
        val first = video.first()
        if (!first.keyFrame) return
        // End each segment immediately before the next IDR. Never advertise a mid-GOP segment.
        val boundary = video.firstOrNull { it.keyFrame && it.ptsUs - first.ptsUs >= 800_000 } ?: return
        if (latestAudioUs < boundary.ptsUs - 50_000) return
        val videoSamples = mutableListOf<BakedCastTransportStream.Sample>()
        while (video.isNotEmpty() && video.first().ptsUs < boundary.ptsUs) videoSamples += video.removeFirst()
        while (commentary.isNotEmpty() && commentary.first().ptsUs < first.ptsUs) commentary.removeFirst()
        val audioSamples = mutableListOf<BakedCastTransportStream.Sample>()
        while (commentary.isNotEmpty() && commentary.first().ptsUs < boundary.ptsUs) audioSamples += commentary.removeFirst()
        require(audioSamples.isNotEmpty()) { "The Cast segment has no commentary" }
        val duration = boundary.ptsUs - first.ptsUs
        hls.append(videoSamples + audioSamples, duration)
        published++
        publishedDurationUs += duration
        val now = System.nanoTime()
        val intervalMs = if (lastPublicationNs == 0L) 0L else (now - lastPublicationNs) / 1_000_000
        lastPublicationNs = now
        if (!firstSegment.isCompleted && published >= 3 && publishedDurationUs >= 3_000_000L) firstSegment.complete(Unit)
        if (published <= 3L || published % 5L == 0L) Log.i("PitwallCast", "BAKED_CAST_SEGMENT count=$published durationUs=$duration intervalMs=$intervalMs ${health()} " +
            "picture=${width}x$height feeds=$feeds audioFrames=${audioSamples.size} videoFrames=${videoSamples.size} transport=HLS_AVC_AAC")
        }
    }

    suspend fun awaitPlayable() = firstSegment.await()

    fun captureFailed(reason: String) = fail("Visible scene capture: $reason")

    private fun fail(reason: String) {
        synchronized(this) {
            if (stopped) return
            stopped = true
            if (!firstSegment.isCompleted) firstSegment.completeExceptionally(IllegalStateException(reason))
        }
        Log.w("PitwallCast", "BAKED_CAST_FAILURE ${reason.take(160)}")
        onFailure(reason)
    }

    private var closed = false

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        stopped = true
        if (!firstSegment.isCompleted) firstSegment.cancel()
        audioJob.cancel()
        runCatching { hls.end() }; runCatching { hls.close() }
        runCatching { audio?.close() }
        audio = null
        video.clear(); commentary.clear()
    }
}
