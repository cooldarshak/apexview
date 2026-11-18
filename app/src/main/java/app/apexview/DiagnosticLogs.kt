package app.apexview

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private var generation = 0
    var status by mutableStateOf("Logging is off")
        private set

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun startIfEnabled(context: Context) {
        if (isEnabled(context)) start(context.applicationContext)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) start(context.applicationContext) else stop()
    }

    private fun publishStatus(value: String) { main.post { status = value } }

    private fun start(context: Context) {
        val token: Int
        synchronized(lock) {
            if (worker?.isAlive == true) return
            token = ++generation
            worker = Thread({ capture(context, token) }, "PitwallDiagnostics").apply { isDaemon = true; start() }
        }
        publishStatus("Starting logging…")
    }

    private fun capture(context: Context, token: Int) {
        try {
            synchronized(lock) {
                if (token != generation) return
                openFile(context)
                writeLocked(context, "\n=== Pitwall logging started ${Instant.now()} pid=${Process.myPid()} ===\n")
                writeLocked(context, "${CrashReports.about()} · build=${BuildConfig.BUILT_AT} · device=${Build.DEVICE}\n")
                writeLocked(context, "App process logcat only; system-wide logs and dumpsys require external access.\n")
                writer?.flush()
            }
            publishStatus("Recording to Download/Pitwall")
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
            val child = ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}", "-T", "1").redirectErrorStream(true).start()
            synchronized(lock) {
                if (token != generation) { child.destroy(); return }
                process = child
            }
            child.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(lock) {
                        if (token != generation) return
                        writeLocked(context, line + "\n")
                        // Logs remain useful after an unexpected process termination.
                        writer?.flush()
                    }
                }
            }
            synchronized(lock) {
                if (token == generation) {
                    writeLocked(context, "Logcat capture ended unexpectedly. Re-enable logging to retry.\n")
                    writer?.flush()
                    publishStatus("Log capture stopped. Turn off and on to retry.")
                }
            }
        } catch (e: Exception) {
            synchronized(lock) { if (token == generation) publishStatus("Logging error: ${e.javaClass.simpleName}. Turn off and on to retry.") }
            Log.e("PitwallDiagnostics", "Log capture failed", e)
        } finally {
            synchronized(lock) {
                if (token == generation) {
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
        if (uri == null || size == null || size >= MAX_BYTES) {
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
    }

    private fun writeLocked(context: Context, text: String) {
        val safe = DiagnosticRedaction.redact(text.take(64 * 1024))
        val encodedSize = safe.toByteArray(Charsets.UTF_8).size
        if (bytes > 0 && bytes + encodedSize > MAX_BYTES) {
            writer?.close(); writer = null
            openFile(context, rotate = true)
        }
        writer?.write(safe)
        bytes += encodedSize
    }

    /** Used for diagnostics outside logcat and the uncaught-exception handler. */
    fun append(context: Context, text: String) {
        synchronized(lock) {
            if (writer == null) return
            runCatching { writeLocked(context.applicationContext, "$text\n"); writer?.flush() }
        }
    }

    fun stop() {
        synchronized(lock) {
            ++generation
            runCatching { writer?.write("=== Logging stopped ${Instant.now()} ===\n"); writer?.close() }
            writer = null
            process?.destroy(); process = null
            worker = null
        }
        publishStatus("Logging is off")
    }

}

/** Remove common credentials and signed addresses before writing public Downloads files. */
internal object DiagnosticRedaction {
    fun redact(text: String): String = text
        .replace(Regex("""(?i)https?://[^\s"'<>]+"""), "[URL]")
        .replace(Regex("(?i)\\b(bearer)\\s+[^\\s,;]+"), "$1 [redacted]")
        .replace(Regex("(?i)\\b(cookie|set-cookie|authorization)\\s*[:=][^\\r\\n]*"), "$1=[redacted]")
        .replace(Regex("(?i)([\"']?(?:ascendontoken|entitlementtoken|access[_-]?token|refresh[_-]?token|password|session[_-]?token)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;\"'}]+"), "$1[redacted]")
        .replace(Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"), "[token]")
}
