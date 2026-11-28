package app.apexview.cast

/** Only the session lock owns this queue. A ready GOP is transferred to the mux worker;
 * network publication and transport packing never execute under that lock. */
internal class BakedCastSegmentQueue(
    private val maxVideo: Int = 349,
    private val maxAudio: Int = 2048,
    private val maxBytes: Long = 48L * 1024 * 1024,
    private val nativeRequested: () -> Boolean = { false },
    private val maxRetainedUs: Long = 15_000_000L,
) {
    data class Ready(val video: List<BakedCastTransportStream.Sample>,
                     val audio: List<BakedCastTransportStream.Sample>, val durationUs: Long) {
        val bytes = video.sumOf { it.bytes.size.toLong() } + audio.sumOf { it.bytes.size.toLong() }
    }
    private val publishing = java.util.IdentityHashMap<Ready, Long>()
    var publishingBytes = 0L
        private set
    val bytesInFlight get() = pendingBytes + publishingBytes
    private fun remainingBytes() = maxBytes - bytesInFlight
    private fun recordMemory() = app.apexview.MediaMemoryAccounting.process.record(this,
        app.apexview.MediaMemoryAccounting.Kind.BAKED_QUEUE, bytesInFlight, publishingBytes)
    private val video = ArrayDeque<BakedCastTransportStream.Sample>()
    private val audio = ArrayDeque<BakedCastTransportStream.Sample>()
    var latestVideoUs = -1L
        private set
    var latestAudioUs = -1L
        private set
    var pendingBytes = 0L
        private set
    val videoCount get() = video.size
    val audioCount get() = audio.size

    // Both pending and publisher-owned arrays count until the mux acknowledges retirement.
    // A stalled publisher cannot accumulate arbitrarily old media even at low bitrates.
    private fun retainedSpanUs(firstUs: Long = -1, lastUs: Long = -1): Long {
        var low = Long.MAX_VALUE
        var high = -1L
        fun include(first: Long, last: Long) {
            if (first >= 0) { low = minOf(low, first); high = maxOf(high, last) }
        }
        include(firstUs, lastUs)
        video.firstOrNull()?.let { include(it.ptsUs, video.last().ptsUs) }
        audio.firstOrNull()?.let { include(it.ptsUs, audio.last().ptsUs) }
        publishing.keys.forEach { ready ->
            ready.video.firstOrNull()?.let { include(it.ptsUs, ready.video.last().ptsUs) }
            ready.audio.firstOrNull()?.let { include(it.ptsUs, ready.audio.last().ptsUs) }
        }
        return if (high < 0) 0 else high - low
    }
    val mediaSpanUs get() = retainedSpanUs()
    init { require(maxRetainedUs > 0) }

    fun offerVideo(sample: BakedCastTransportStream.Sample) {
        require(sample.video && sample.ptsUs >= 0 && sample.ptsUs > latestVideoUs) {
            "The scene encoder did not produce monotonically increasing timestamps"
        }
        if (!sample.keyFrame && video.isEmpty()) {
            latestVideoUs = sample.ptsUs
            sample.bytes.fill(0)
            return
        }
        require(video.size < maxVideo && sample.bytes.size.toLong() <= remainingBytes()) {
            "Baked Cast video publication backlog exhausted"
        }
        require(retainedSpanUs(sample.ptsUs, sample.ptsUs) <= maxRetainedUs) {
            "Baked Cast media-time publication backlog exhausted"
        }
        latestVideoUs = sample.ptsUs
        video.addLast(sample)
        pendingBytes += sample.bytes.size
        recordMemory()
    }

    private var audioMetadata = LongArray(0)
    private var audioNative: Boolean? = null
    fun offerAudio(samples: List<BakedCastTransportStream.Sample>) {
        if (samples.isEmpty()) return
        // Validate the entire batch before mutation. Invalid samples must not
        // leave a half-enqueued batch or unaccounted retained buffers.
        require(samples.size <= maxAudio - audio.size && samples.none { it.video })
        val requested = nativeRequested()
        val result = if (requested && audioNative != false && samples.size <= 2048) {
            if (audioMetadata.size < samples.size * 2) audioMetadata = LongArray(samples.size * 2)
            samples.forEachIndexed { i, sample ->
                audioMetadata[i * 2] = sample.ptsUs
                audioMetadata[i * 2 + 1] = sample.bytes.size.toLong()
            }
            app.apexview.tiled.NativeHotPath.audioAdmit(audioMetadata, samples.size,
                latestAudioUs, remainingBytes(), maxAudio - audio.size)
        } else null
        if (requested && audioNative != (result != null)) {
            audioNative = result != null
            android.util.Log.i("PitwallNativeHotPath", "AUDIO_QUEUE_ENGINE requested=RUST actual=" +
                if (result != null) "RUST reason=BATCH_VALIDATED" else "JVM reason=NATIVE_UNAVAILABLE")
        } else if (!requested) { audioNative = null; if (audioMetadata.isNotEmpty()) audioMetadata = LongArray(0) }
        val accepted = if (result != null) {
            require(result >= 0L) { "Baked Cast commentary admission rejected" }
            result
        } else {
            var last = latestAudioUs
            var total = 0L
            for (sample in samples) {
                require(sample.ptsUs >= 0 && sample.ptsUs > last && sample.bytes.isNotEmpty()) {
                    "Baked Cast commentary timestamps are not increasing or sample is empty"
                }
                total = Math.addExact(total, sample.bytes.size.toLong())
                require(total <= remainingBytes()) { "Baked Cast commentary publication backlog exhausted" }
                last = sample.ptsUs
            }
            total
        }
        require(retainedSpanUs(samples.first().ptsUs, samples.last().ptsUs) <= maxRetainedUs) {
            "Baked Cast media-time publication backlog exhausted"
        }
        for (sample in samples) {
            audio.addLast(sample)
            latestAudioUs = sample.ptsUs
        }
        pendingBytes += accepted
        recordMemory()
    }

    private fun remove(queue: ArrayDeque<BakedCastTransportStream.Sample>) =
        queue.removeFirst().also { pendingBytes -= it.bytes.size }

    fun takeReady(): Ready? {
        val first = video.firstOrNull() ?: return null
        check(first.keyFrame)
        val boundary = video.firstOrNull { it.keyFrame && it.ptsUs - first.ptsUs >= 800_000L } ?: return null
        if (audio.isEmpty() || latestAudioUs < boundary.ptsUs - 50_000L) return null
        val duration = boundary.ptsUs - first.ptsUs
        require(duration in 800_000L..4_000_000L) { "Baked Cast GOP exceeds segment duration" }
        val picture = mutableListOf<BakedCastTransportStream.Sample>()
        while (video.isNotEmpty() && video.first().ptsUs < boundary.ptsUs) picture += remove(video)
        while (audio.isNotEmpty() && audio.first().ptsUs < first.ptsUs) remove(audio).bytes.fill(0)
        val commentary = mutableListOf<BakedCastTransportStream.Sample>()
        while (audio.isNotEmpty() && audio.first().ptsUs < boundary.ptsUs) commentary += remove(audio)
        recordMemory()
        if (commentary.isEmpty()) {
            picture.forEach { it.bytes.fill(0) }
            throw IllegalArgumentException("The Cast segment has no commentary")
        }
        return Ready(picture, commentary, duration).also {
            publishing[it] = it.bytes
            publishingBytes += it.bytes
            recordMemory()
        }
    }

    /** Publisher owns these arrays until mux completes, including cancellation. */
    fun finish(ready: Ready) {
        val bytes = publishing.remove(ready) ?: return
        publishingBytes -= bytes
        recordMemory()
    }

    fun clear() {
        video.forEach { it.bytes.fill(0) }; audio.forEach { it.bytes.fill(0) }
        video.clear(); audio.clear(); pendingBytes = 0L
        audioMetadata = LongArray(0)
        recordMemory()
    }
}
