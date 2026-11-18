package app.apexview

internal class DiagnosticHistory {
    private data class Burst(var repeats: Int = 0, var last: String = "")
    private val bursts = LinkedHashMap<String, Burst>()
    private val milestones = ArrayDeque<String>()
    private var replayStart: String? = null

    fun accept(line: String): String? {
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

    fun drain(): List<String> = bursts.values.mapNotNull {
        if (it.repeats == 0) null else "BUFFER_QUEUE_TIMEOUT repeats=${it.repeats} last=${it.last}"
    }.also { bursts.clear() }

    fun retain(line: String) {
        if ("Starting timing load for content=" in line) replayStart = line.take(1024)
        if (("ApexviewTiming:" in line && "heapUsedMiB=" !in line) ||
            ("PitwallPlayback:" in line && "event=sample" !in line && "event=scene" !in line) ||
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
    }
}
