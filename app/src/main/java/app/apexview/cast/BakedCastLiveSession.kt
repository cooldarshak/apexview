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
    nativeAudioRequested: Boolean = false,
    nativeContext: android.content.Context? = null,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    @Volatile private var nativeAudioPreference = nativeAudioRequested
    private val hls = BakedCastHlsSession(address, peer, tls, { nativeAudioPreference }, nativeContext)
    internal fun nativeProcessingRequested(): Boolean = nativeAudioPreference
    fun setNativePreference(value: Boolean) { nativeAudioPreference = value }
    private val audioReader = reader
    private val audioSource = audioUri
    private var audio: BakedCastArchiveAudio? = null
    private var sourceStartMs = -1L
    private val firstSegment = CompletableDeferred<Unit>()
    private val audioRetired = CompletableDeferred<Boolean>()
    private val publisherRetired = CompletableDeferred<Boolean>()
    private val pending = BakedCastSegmentQueue(nativeRequested = { nativeAudioPreference })
    // A notification is only a wakeup; the bounded queue owns every sample.
    private val publishRequests = Channel<Unit>(Channel.CONFLATED)
    private var stopped = false
    private var published = 0L
    private var publishedDurationUs = 0L
    private var lastPublicationNs = 0L
    @Volatile var receiverRendering = false
    @Volatile private var receiverBuffering = false
    private val pressure = BakedProducerPressure()
    /** Main owner polls this before changing its one existing player's clock.
     * Pressure is cleared only by publication drain, not by dropping samples. */
    @Synchronized fun producerPressure(nowMs: Long): BakedProducerPressure.State =
        if (stopped) BakedProducerPressure.State.RUN else pressure.update(nowMs, pending.pressureHigh(), pending.pressureLow())
    fun updateReceiverBuffering(buffering: Boolean) { receiverBuffering = buffering }
    @Synchronized fun receiverNeedsCapture(): Boolean =
        !stopped && receiverBuffering && pending.publishingBytes == 0L
    @Synchronized fun health(): String {
        val publicationAgeMs = if (lastPublicationNs == 0L) -1L else
            (System.nanoTime() - lastPublicationNs).coerceAtLeast(0L) / 1_000_000L
        return "publicationAgeMs=$publicationAgeMs segments=$published mediaUs=$publishedDurationUs windowUs=${hls.advertisedDurationUs} videoUs=${pending.latestVideoUs} audioUs=${pending.latestAudioUs} queuedVideo=${pending.videoCount} queuedAudio=${pending.audioCount} queuedBytes=${pending.pendingBytes} publishingBytes=${pending.publishingBytes} bytesInFlight=${pending.bytesInFlight} queuedMediaSpanUs=${pending.mediaSpanUs}"
    }
    var width = 0
        private set
    var height = 0
        private set
    val url get() = hls.url
    internal val diagnosticRelayId get() = hls.diagnosticRelayId
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
                    try { hls.append(ready.video + ready.audio, ready.durationUs) }
                    finally {
                        // Ready owns the encoder and AAC samples, not the newly
                        // packed immutable TS. No consumer can still read these.
                        ready.video.forEach { it.bytes.fill(0) }
                        ready.audio.forEach { it.bytes.fill(0) }
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
            var consecutiveFailures = 0
            while (true) {
                ensureActive()
                val request = synchronized(this@BakedCastLiveSession) {
                    if (stopped) return@launch
                    audio?.let {
                        val origin = Math.multiplyExact(sourceStartMs, 1000L)
                        val horizon = Math.addExact(origin, pending.latestVideoUs.coerceAtLeast(0) + 5_000_000L)
                        val limit = pending.audioEndLimitUs()
                        Triple(it, horizon, if (limit > Long.MAX_VALUE - origin) Long.MAX_VALUE else origin + limit)
                    }
                }
                if (request == null) { delay(50); continue }
                // Fetch a short distance ahead of the captured picture, not the entire replay.
                val samples = try {
                    request.first.readUntil(request.second, request.third).also { consecutiveFailures = 0 }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val backoff = BakedAudioRecovery.retryDelayMs(failure, consecutiveFailures)
                        ?: throw failure
                    consecutiveFailures++
                    Log.w("PitwallCast", "BAKED_AUDIO_RETRY attempt=$consecutiveFailures delayMs=$backoff")
                    delay(backoff)
                    continue
                }
                synchronized(this@BakedCastLiveSession) {
                    if (stopped) {
                        samples.forEach { it.bytes.fill(0) }
                        return@launch
                    }
                    try { pending.offerAudio(samples) }
                    catch (failure: Throwable) {
                        samples.forEach { it.bytes.fill(0) }
                        throw failure
                    }
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
            audioRetired.complete(runCatching { retired?.close() }.isSuccess)
        }
    }

    @Synchronized fun setOutputSize(w: Int, h: Int, playbackPositionMs: Long) {
        require(w > 0 && h > 0 && playbackPositionMs >= 0)
        check(!stopped && audioJob.isActive && publisherJob.isActive && audio == null) { "Clear Cast output already anchored or closed" }
        val sourceStartUs = Math.multiplyExact(playbackPositionMs, 1_000L)
        width = w; height = h
        sourceStartMs = playbackPositionMs
        audio = BakedCastArchiveAudio(audioReader, audioSource, sourceStartUs, { nativeAudioPreference })
        Log.i("PitwallCast", "BAKED_CAST_SOURCE_CLOCK startMs=$sourceStartMs encoderPicture=${width}x$height")
    }

    /** GL encoder callback: validate/transfer and signal only, never mux or publish. */
    fun videoSample(sample: BakedCastTransportStream.Sample) {
        try {
            synchronized(this) {
                if (stopped) { sample.bytes.fill(0); return }
                try { pending.offerVideo(sample) }
                catch (failure: Throwable) {
                    sample.bytes.fill(0)
                    throw failure
                }
                publishRequests.trySend(Unit)
            }
        } catch (error: Exception) { fail("Scene Cast encoder: ${error.message ?: error.javaClass.simpleName}") }
    }

    suspend fun awaitPlayable() = firstSegment.await()

    /** A replacement must not overlap a blocked mux or commentary reader. */
    suspend fun awaitRetired(): Boolean {
        val audioDone = audioRetired.await()
        return publisherRetired.await() && audioDone
    }

    fun captureFailed(reason: String) = fail("Visible scene capture: $reason")

    private fun fail(reason: String) {
        synchronized(this) {
            if (stopped) return
            stopped = true
            pending.clear()
            publishRequests.close()
            if (!firstSegment.isCompleted) firstSegment.completeExceptionally(IllegalStateException(reason))
        }
        Log.w("PitwallCast", "BAKED_CAST_FAILURE ${reason.take(160)}")
        // Terminal producer failure cannot retain the relay if the notification
        // throws or a superseded UI owner discards it.
        close()
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
        // Cancel first, then close after actual publisher retirement. Calling
        // synchronized HLS close on Main could wait for the complete mux.
        publisherJob.invokeOnCompletion {
            val closed = runCatching { hls.close() }.isSuccess
            publisherRetired.complete(closed && synchronized(this) { pending.bytesInFlight == 0L })
        }
        publisherJob.cancel()
    }
}
