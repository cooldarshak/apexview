package app.apexview

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Timer
import kotlin.concurrent.timerTask

/** Opt-in, process-scoped logcat capture. Android does not grant apps system-wide log access. */
object DiagnosticLogs {
    private const val PREFS = "pitwall_diagnostics"
    private const val ENABLED = "enabled"
    private const val URI = "active_uri"
    private const val MAX_BYTES = 5L * 1024 * 1024
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var process: java.lang.Process? = null
    private var writer: BufferedWriter? = null
    private var bytes = 0L
    @Volatile private var generation = 0
    private var segment = 0
    private var captureId = ""
    private var dirty = false
    private val history = DiagnosticHistory()
    private val appendQueue = DiagnosticAppendQueue()
    @Volatile var recording = false
        private set
    var status by mutableStateOf("Logging is off")
        private set

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun startIfEnabled(context: Context) {
        if (isEnabled(context)) start(context.applicationContext)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) start(context.applicationContext) else stop(context.applicationContext)
    }

    private fun publishStatus(value: String, token: Int) {
        main.post { if (token == generation) status = value }
    }

    private fun start(context: Context) {
        val token: Int
        synchronized(lock) {
            if (worker?.isAlive == true) return
            token = ++generation
            publishStatus("Starting logging…", token)
            worker = Thread({ capture(context, token) }, "PitwallDiagnostics").apply { isDaemon = true; start() }
        }
    }

    private fun capture(context: Context, token: Int) {
        val timer = Timer("PitwallLogFlush", true)
        try {
            val child = ProcessBuilder("logcat", "-b", "main", "-b", "system", "-b", "crash",
                "-v", "threadtime", "--pid=${Process.myPid()}", "-T", "2000").redirectErrorStream(true).start()
            synchronized(lock) {
                if (token != generation) { child.destroy(); return }
                process = child
                segment = 0
                captureId = "${Process.myPid()}-${SystemClock.elapsedRealtime()}"
                history.clear()
                openFile(context)
                flushLocked()
                appendQueue.start(token)
                recording = true
            }
            timer.scheduleAtFixedRate(timerTask {
                synchronized(lock) {
                    if (token != generation) return@synchronized
                    try {
                        flushSummariesLocked(context, token)
                    } catch (e: Exception) {
                        Log.e("PitwallDiagnostics", "Periodic write failed", e)
                        child.destroy()
                    }
                }
            }, 1_000, 1_000)
            // PSS is an Android process estimate, not an addition to RSS or
            // allocator counters. Sample off the UI thread only while recording.
            timer.schedule(timerTask {
                if (token != generation) return@timerTask
                val line = runCatching { ProcessMemoryDiagnostic.sample(context) }
                    .getOrElse { "GC_PROCESS_MEMORY unavailable=${it.javaClass.simpleName}" }
                synchronized(lock) {
                    if (token == generation) runCatching { writeLocked(context, "$line\n") }
                }
            }, 0L, 10_000L)
            publishStatus("Recording to Download/Pitwall", token)
            if (Build.VERSION.SDK_INT >= 30) {
                runCatching {
                    val manager = context.getSystemService(android.app.ActivityManager::class.java)
                    manager?.getHistoricalProcessExitReasons(null, 0, 3).orEmpty().forEach { exit ->
                        append(context, "PREVIOUS_PROCESS_EXIT pid=${exit.pid} time=${Instant.ofEpochMilli(exit.timestamp)} " +
                            "reason=${exit.reason} status=${exit.status} pssKiB=${exit.pss} rssKiB=${exit.rss}")
                    }
                }.onFailure { append(context, "PROCESS_EXIT_QUERY_FAILED ${it.javaClass.simpleName}") }
            }
            // Capabilities describe what the device advertises; actual decoder use is logged by playback.
            runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
                    .filter { info -> info.supportedTypes.any { it.equals("video/hevc", true) || it.equals("video/avc", true) } }
                    .forEach { info -> append(context, "CODEC_AVAILABLE name=${info.name} types=${info.supportedTypes.joinToString()}") }
            }.onFailure { append(context, "CODEC_ENUMERATION_FAILED ${it.javaClass.simpleName}: ${it.message}") }
            child.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(lock) {
                        if (token != generation) return
                        val captured = history.accept(line) ?: return@synchronized
                        writeLocked(context, "$captured\n")
                        if (" F " in captured || "FATAL EXCEPTION" in captured) flushLocked()
                    }
                }
            }
            synchronized(lock) {
                if (token == generation) {
                    writeLocked(context, "CAPTURE_ENDED utc=${Instant.now()}\n")
                    flushSummariesLocked(context, token, closeQueue = true)
                    publishStatus("Log capture stopped. Turn off and on to retry.", token)
                }
            }
        } catch (e: Exception) {
            synchronized(lock) { if (token == generation) publishStatus("Logging error: ${e.javaClass.simpleName}. Turn off and on to retry.", token) }
            Log.e("PitwallDiagnostics", "Log capture failed", e)
        } finally {
            timer.cancel()
            synchronized(lock) {
                if (token == generation) {
                    recording = false
                    runCatching { if (writer != null) flushSummariesLocked(context, token, closeQueue = true) }
                    appendQueue.drain(token, close = true)
                    runCatching { writer?.close() }; writer = null
                    process?.destroy(); process = null
                    worker = null
                }
            }
        }
    }

    private fun openFile(context: Context, rotate: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val resolver = context.contentResolver
        var uri = if (rotate) null else prefs.getString(URI, null)?.let(Uri::parse)
        var size = uri?.let { existing ->
            runCatching { resolver.query(existing, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else null
            } }.getOrNull()
        }
        if (uri == null || size == null || size > MAX_BYTES - 2_048L) {
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "pitwall-$stamp.log")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Pitwall/")
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create a log in Download/Pitwall")
            size = 0L
            prefs.edit().putString(URI, uri.toString()).apply()
        }
        val output = resolver.openOutputStream(uri, "wa") ?: error("Could not open diagnostic log")
        writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
        bytes = size
        segment++
        writeSafe("\n=== PITWALL_CAPTURE id=$captureId segment=$segment utc=${Instant.now()} uptimeMs=${SystemClock.elapsedRealtime()} ===\n")
        writeSafe("${CrashReports.about()} · build=${BuildConfig.BUILT_AT} · device=${Build.DEVICE}\n")
        writeSafe("Scope: app process logcat, buffers main/system/crash. Surface timeout repeats are counted.\n")
        if (rotate) {
            writeSafe("=== CONTEXT FROM PRIOR SEGMENTS ===\n")
            history.context().forEach { writeSafe("$it\n") }
            writeSafe("=== END CONTEXT ===\n")
        }
    }

    private fun writeSafe(text: String, size: Int = diagnosticUtf8Size(text)) {
        writer?.write(text)
        bytes += size
        dirty = true
    }

    private fun flushLocked() {
        if (dirty) writer?.flush()
        dirty = false
    }

    private fun flushSummariesLocked(context: Context, token: Int, closeQueue: Boolean = false) {
        val pending = appendQueue.drain(token, close = closeQueue)
        pending.lines.forEach { writeLocked(context, "$it\n") }
        if (pending.rejected > 0)
            writeLocked(context, "DIAGNOSTIC_DIRECT_QUEUE_OVERFLOW rejected=${pending.rejected}\n")
        history.drain().forEach { writeLocked(context, "$it\n") }
        flushLocked()
    }

    private fun writeLocked(context: Context, text: String) {
        val safe = DiagnosticRedaction.redact(text.take(64 * 1024))
        val encodedSize = diagnosticUtf8Size(safe)
        if (bytes > 0 && bytes + encodedSize > MAX_BYTES) {
            writer?.close(); writer = null
            openFile(context, rotate = true)
        }
        writeSafe(safe, encodedSize)
        history.retain(safe.trimEnd())
    }

    /** Normal producers never take the disk writer lock. Fatal crash capture is durable best effort. */
    fun append(context: Context, text: String) {
        if (!text.startsWith("FATAL EXCEPTION")) {
            appendQueue.offer(text)
            return
        }
        synchronized(lock) {
            if (writer == null) return
            runCatching {
                flushSummariesLocked(context.applicationContext, generation)
                writeLocked(context.applicationContext, "$text\n")
                flushLocked()
            }
        }
    }

    private fun stop(context: Context) {
        val stoppedToken: Int
        synchronized(lock) {
            val oldToken = generation
            stoppedToken = ++generation
            recording = false
            runCatching {
                if (writer != null) {
                    flushSummariesLocked(context, oldToken, closeQueue = true)
                    writeLocked(context, "=== Logging stopped ${Instant.now()} ===\n")
                    flushLocked()
                }
            }
            appendQueue.drain(oldToken, close = true)
            runCatching { writer?.close() }
            writer = null
            process?.destroy(); process = null
            worker = null
        }
        publishStatus("Logging is off", stoppedToken)
    }

}

/** Match String.toByteArray(UTF_8).size without allocating a second full
 * encoded copy before the existing UTF-8 writer encodes the same string.
 * The Java UTF-8 encoder replaces malformed UTF-16 code units with one byte.
 * Diagnostic callers cap each input at 64 Ki UTF-16 units.
 */
internal fun diagnosticUtf8Size(text: String): Int {
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val c = text[index++]
        bytes += when {
            c.code <= 0x7f -> 1
            c.code <= 0x7ff -> 2
            Character.isHighSurrogate(c) -> {
                if (index < text.length && Character.isLowSurrogate(text[index])) {
                    index++
                    4
                } else 1
            }
            Character.isLowSurrogate(c) -> 1
            else -> 3
        }
    }
    return bytes
}

/** Remove common credentials and signed addresses before writing public Downloads files. */
internal object DiagnosticRedaction {
    private val patterns = listOf(
        Regex("""(?i)https?://[^\s"'<>]+""") to "[URL]",
        Regex("(?i)\\b(bearer)\\s+[^\\s,;]+") to "$1 [redacted]",
        Regex("(?i)\\b(cookie|set-cookie|authorization)\\s*[:=][^\\r\\n]*") to "$1=[redacted]",
        Regex("(?i)([\"']?(?:ascendontoken|entitlementtoken|access[_-]?token|refresh[_-]?token|password|session[_-]?token)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;\"'}]+") to "$1[redacted]",
        Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b") to "[token]",
    )

    fun redact(text: String): String = patterns.fold(text) { value, (pattern, replacement) ->
        pattern.replace(value, replacement)
    }
}
