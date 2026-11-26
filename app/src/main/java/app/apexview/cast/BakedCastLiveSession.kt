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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive

/** One continuously captured, baked phone picture + original authorized AAC commentary. */
internal class BakedCastLiveSession(
    address: String, peer: InetAddress?, reader: CmafHttpReader, audioUri: Uri,
    val programmeLabel: String, val feeds: Int, scope: CoroutineScope,
    tls: app.apexview.cast.local.PitwallTls.Session? = null,
    private val nativeAudioRequested: Boolean = false,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val hls = BakedCastHlsSession(address, peer, tls)
    private val audioReader = reader
    private val audioSource = audioUri
    private var audio: BakedCastArchiveAudio? = null
    private var sourceStartMs = -1L
    private val firstSegment = CompletableDeferred<Unit>()
    private val pending = BakedCastSegmentQueue()
    // A notification is only a wakeup; the bounded queue owns every sample.
    private val publishRequests = Channel<Unit>(Channel.CONFLATED)
    private var stopped = false
    private var published = 0L
    private var publishedDurationUs = 0L
    private var lastPublicationNs = 0L
    @Volatile var receiverRendering = false
    @Volatile var previewSuppressionRequested = false
    @Synchronized fun health(): String = "segments=$published mediaUs=$publishedDurationUs windowUs=${hls.advertisedDurationUs} videoUs=${pending.latestVideoUs} audioUs=${pending.latestAudioUs} queuedVideo=${pending.videoCount} queuedAudio=${pending.audioCount} queuedBytes=${pending.pendingBytes} publishingBytes=${pending.publishingBytes} bytesInFlight=${pending.bytesInFlight}"
    var width = 0
        private set
    var height = 0
        private set
    val url get() = hls.url
    val playable get() = firstSegment.isCompleted && !firstSegment.isCancelled

    private val publisherJob: Job = scope.launch(Dispatchers.Default) {
        try {
            for (ignored in publishRequests) {
                while (true) {
                    ensureActive()
                    val ready = synchronized(this@BakedCastLiveSession) {
                        if (stopped) null else pending.takeReady()
                    } ?: break
                    val started = System.nanoTime()
                    // Single owner of mux continuity counters and segment ordering.
                    // Never hold the session lock across packing or relay publication.
                    val forecast = app.apexview.tiled.SpeculativeMemoryAdmission.process.beginForeground(ready.bytes)
                    try { hls.append(ready.video + ready.audio, ready.durationUs) }
                    finally {
                        forecast.close()
                        synchronized(this@BakedCastLiveSession) { pending.finish(ready) }
                    }
                    val muxMs = (System.nanoTime() - started) / 1_000_000L
                    synchronized(this@BakedCastLiveSession) {
                        if (stopped) return@launch
                        published++
                        publishedDurationUs += ready.durationUs
                        val now = System.nanoTime()
                        val intervalMs = if (lastPublicationNs == 0L) 0L else (now - lastPublicationNs) / 1_000_000L
                        lastPublicationNs = now
                        if (!firstSegment.isCompleted && BakedCastStartupPolicy.ready(published, publishedDurationUs))
                            firstSegment.complete(Unit)
                        if (published <= 3L || published % 5L == 0L) Log.i("PitwallCast",
                            "BAKED_CAST_SEGMENT count=$published durationUs=${ready.durationUs} intervalMs=$intervalMs muxPublishMs=$muxMs ${health()} " +
                            "picture=${width}x$height feeds=$feeds audioFrames=${ready.audio.size} videoFrames=${ready.video.size} transport=HLS_AVC_AAC")
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail("Baked Cast publication: ${error.javaClass.simpleName}") }
    }

    private val audioJob: Job = scope.launch(Dispatchers.IO) {
        try {
            while (true) {
                val request = synchronized(this@BakedCastLiveSession) {
                    if (stopped) return@launch
                    audio?.let { it to (sourceStartMs * 1000 + pending.latestVideoUs.coerceAtLeast(0) + 5_000_000) }
                }
                if (request == null) { delay(50); continue }
                // Fetch a short distance ahead of the captured picture, not the entire replay.
                val samples = request.first.readUntil(request.second)
                synchronized(this@BakedCastLiveSession) {
                    if (stopped) return@launch
                    pending.offerAudio(samples)
                    publishRequests.trySend(Unit)
                }
                delay(800)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail("Clear archive commentary: ${app.apexview.DiagnosticRedaction.redact(error.message ?: error.javaClass.simpleName)}") }
    }

    init {
        // readUntil is blocking IO. Cancel first; close its state only after that
        // worker really exits, never zero its initialization during a read.
        audioJob.invokeOnCompletion {
            val retired = synchronized(this) { audio.also { audio = null } }
            runCatching { retired?.close() }
        }
    }

    @Synchronized fun setOutputSize(w: Int, h: Int, playbackPositionMs: Long) {
        require(w > 0 && h > 0 && playbackPositionMs >= 0)
        check(!stopped && audioJob.isActive && publisherJob.isActive && audio == null) { "Clear Cast output already anchored or closed" }
        val sourceStartUs = Math.multiplyExact(playbackPositionMs, 1_000L)
        width = w; height = h
        sourceStartMs = playbackPositionMs
        audio = BakedCastArchiveAudio(audioReader, audioSource, sourceStartUs, nativeAudioRequested)
        Log.i("PitwallCast", "BAKED_CAST_SOURCE_CLOCK startMs=$sourceStartMs encoderPicture=${width}x$height")
    }

    /** GL encoder callback: validate/transfer and signal only, never mux or publish. */
    fun videoSample(sample: BakedCastTransportStream.Sample) {
        try {
            synchronized(this) {
                if (stopped) return
                pending.offerVideo(sample)
                publishRequests.trySend(Unit)
            }
        } catch (error: Exception) { fail("Scene Cast encoder: ${error.message ?: error.javaClass.simpleName}") }
    }

    suspend fun awaitPlayable() = firstSegment.await()

    fun captureFailed(reason: String) = fail("Visible scene capture: $reason")

    private fun fail(reason: String) {
        synchronized(this) {
            if (stopped) return
            stopped = true
            publishRequests.close()
            if (!firstSegment.isCompleted) firstSegment.completeExceptionally(IllegalStateException(reason))
        }
        Log.w("PitwallCast", "BAKED_CAST_FAILURE ${reason.take(160)}")
        onFailure(reason)
    }

    private var closed = false

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            stopped = true
            if (!firstSegment.isCompleted) firstSegment.cancel()
            pending.clear()
            publishRequests.close()
        }
        audioJob.cancel()
        publisherJob.cancel()
        // The HLS object's lock serializes at most the one already-running mux.
        // No session/GL lock is held while waiting for its retirement.
        runCatching { hls.end() }; runCatching { hls.close() }
    }
}
