package app.apexview.cast

/** Only the session lock owns this queue. A ready GOP is transferred to the mux worker;
 * network publication and transport packing never execute under that lock. */
internal class BakedCastSegmentQueue(
    private val maxVideo: Int = 349,
    private val maxAudio: Int = 2048,
    private val maxBytes: Long = 48L * 1024 * 1024,
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

    fun offerVideo(sample: BakedCastTransportStream.Sample) {
        require(sample.video && sample.ptsUs >= 0 && sample.ptsUs > latestVideoUs) {
            "The scene encoder did not produce monotonically increasing timestamps"
        }
        latestVideoUs = sample.ptsUs
        if (!sample.keyFrame && video.isEmpty()) return
        require(video.size < maxVideo && sample.bytes.size.toLong() <= remainingBytes()) {
            "Baked Cast video publication backlog exhausted"
        }
        video.addLast(sample)
        pendingBytes += sample.bytes.size
        recordMemory()
    }

    fun offerAudio(samples: List<BakedCastTransportStream.Sample>) {
        for (sample in samples) {
            require(!sample.video && sample.ptsUs >= 0 && sample.ptsUs > latestAudioUs) {
                "Baked Cast commentary timestamps are not increasing"
            }
            require(audio.size < maxAudio && sample.bytes.size.toLong() <= remainingBytes()) {
                "Baked Cast commentary publication backlog exhausted"
            }
            audio.addLast(sample)
            pendingBytes += sample.bytes.size
            latestAudioUs = sample.ptsUs
            recordMemory()
        }
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
        while (audio.isNotEmpty() && audio.first().ptsUs < first.ptsUs) remove(audio)
        val commentary = mutableListOf<BakedCastTransportStream.Sample>()
        while (audio.isNotEmpty() && audio.first().ptsUs < boundary.ptsUs) commentary += remove(audio)
        recordMemory()
        require(commentary.isNotEmpty()) { "The Cast segment has no commentary" }
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
        video.clear(); audio.clear(); pendingBytes = 0L
        recordMemory()
    }
}
