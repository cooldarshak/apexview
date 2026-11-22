package app.apexview

internal class DiagnosticHistory {
    private data class Burst(var repeats: Int = 0, var last: String = "")
    private val bursts = LinkedHashMap<String, Burst>()
    private val milestones = ArrayDeque<String>()
    private var replayStart: String? = null
    private var repeatedFrameworkRateHints = 0
    private var latestFrameworkRateHint: String? = null

    fun accept(line: String): String? {
        // Android 16/Compose prints this platform diagnostic at display cadence.
        // Retain the first exact record, count every following repeat, and
        // preserve the last full record in the periodic summary. This keeps
        // important codec/HEVC lines in the 5MB rotating capture.
        if ("View    : setRequestedFrameRate frameRate=NaN" in line &&
            "androidx.compose.ui.platform." in line) {
            repeatedFrameworkRateHints++
            latestFrameworkRateHint = line
            if (repeatedFrameworkRateHints > 1) return null
        }
        val tag = line.indexOf("BufferQueueProducer:")
        val timeout = line.indexOf("dequeueBuffer: waitForFreeSlotThenRelock: timeout")
        if (tag < 0 || timeout <= tag) return line
        val surface = line.substring(tag, timeout)
        val burst = bursts[surface]
        if (burst == null) {
            if (bursts.size < 32) bursts[surface] = Burst()
            return line
        }
        burst.repeats++
        burst.last = line
        return null
    }

    fun drain(): List<String> {
        val summaries = bursts.values.mapNotNull {
            if (it.repeats == 0) null else "BUFFER_QUEUE_TIMEOUT repeats=${it.repeats} last=${it.last}"
        }.toMutableList()
        bursts.clear()
        if (repeatedFrameworkRateHints > 1) {
            summaries += "PLATFORM_FRAME_RATE_NAN repeats=${repeatedFrameworkRateHints - 1} " +
                "last=${latestFrameworkRateHint.orEmpty()}"
        }
        repeatedFrameworkRateHints = 0
        latestFrameworkRateHint = null
        return summaries
    }

    fun retain(line: String) {
        if ("Starting timing load for content=" in line) replayStart = line.take(1024)
        if (("ApexviewTiming:" in line && "heapUsedMiB=" !in line) ||
            ("PitwallPlayback:" in line && "event=sample" !in line && "event=scene" !in line) ||
            ("ApexviewSingleDecoder:" in line && ("F1_COMPOSITION_" in line || "ARCHIVE_VOD_FAILED" in line)) ||
            ("PitwallReceiver:" in line && ("VIDEO_DECODER_INIT" in line || "VIDEO_FORMAT" in line || "SCENE_RENDERING_CONFIRMED" in line)) ||
            "PREVIOUS_PROCESS_EXIT" in line
        ) {
            if (milestones.size == 96) milestones.removeFirst()
            milestones.addLast(line.take(1024))
        }
    }

    fun context(): List<String> = (listOfNotNull(replayStart) + milestones).distinct()

    fun clear() {
        bursts.clear()
        milestones.clear()
        replayStart = null
        repeatedFrameworkRateHints = 0
        latestFrameworkRateHint = null
    }
}
