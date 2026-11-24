package app.apexview

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A published version: its number, what changed, and where its APK is. */
class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val filename: String,
    val checksumsUrl: String,
)

/**
 * Keeps the app current from its GitHub releases. Each launch it asks GitHub for the latest
 * release of [BuildConfig.UPDATE_REPO]; if that's newer, a prompt offers it (a version put off
 * with Not now isn't offered again for a day). Update
 * downloads the release's APK and hands it to Android's package installer, which asks the user
 * to confirm (and, the first time, to allow this app to install updates). Releases must be signed
 * with the same key as the installed app, or Android refuses them.
 */
class Updater(context: Context, private val scope: CoroutineScope) {
    // Never retain an Activity across installer confirmation or rotation.
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .followSslRedirects(false) // GitHub asset redirects may stay HTTPS, never downgrade to HTTP.
        .callTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        .build()
    private var checkJob: Job? = null
    private var installJob: Job? = null
    private var closed = false

    /** A newer release to offer, while the prompt is up. */
    var offered by mutableStateOf<Release?>(null)
        private set
    /** Download progress 0..1 while updating; null otherwise. */
    var progress by mutableStateOf<Float?>(null)
        private set
    /** What went wrong, or the result of a check asked for by hand. */
    var note by mutableStateOf<String?>(null)
        private set

    private val statusAction = context.packageName + ".INSTALL_STATUS"
    private val statusUiAction = InstallStatusProtocol.uiAction(context.packageName)

    // Register on application context and release on AppState.close(), including
    // configuration changes. Keeping one anonymous receiver for every recreated
    // activity leaked the old updater and its held activity context.
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_SUCCESS -> {
                    progress = null
                    prefs.edit().remove(InstallStatusProtocol.PREF_FAILED_RESULT).apply()
                }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit // The persistent receiver opens system confirmation.
                else -> {
                    progress = null
                    note = InstallStatusProtocol.failureMessage(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                    prefs.edit().remove(InstallStatusProtocol.PREF_FAILED_RESULT).apply()
                }
            }
        }
    }

    init {
        // PackageInstaller requires an application receiver that survives the
        // temporary foreground-to-system-installer activity transition.
        ContextCompat.registerReceiver(
            context.applicationContext,
            statusReceiver,
            IntentFilter(statusUiAction),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun close() {
        if (closed) return
        closed = true
        checkJob?.cancel()
        installJob?.cancel()
        http.dispatcher.cancelAll()
        runCatching { context.applicationContext.unregisterReceiver(statusReceiver) }
    }

    /** On launch. */
    fun checkOnLaunch() {
        // The manifest receiver can finish while no Activity (and therefore no
        // Compose state observer) is alive; surface failures on next launch.
        prefs.getString(InstallStatusProtocol.PREF_FAILED_RESULT, null)?.let { failure ->
            note = failure
            prefs.edit().remove(InstallStatusProtocol.PREF_FAILED_RESULT).apply()
        }
        startCheck(quiet = true)
    }

    /** From Settings: checks now and says what it found. */
    fun checkNow() {
        startCheck(quiet = false)
    }

    private fun startCheck(quiet: Boolean) {
        if (closed || checkJob?.isActive == true || progress != null) return
        checkJob = scope.launch { check(quiet) }
    }

    private suspend fun check(quiet: Boolean) {
        val release = try {
            latest()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!closed && !quiet) note = "Couldn't check for updates: ${safeError(error)}"
            return
        }
        if (closed || progress != null) return
        prefs.edit().putLong(PREF_CHECKED, System.currentTimeMillis()).apply()
        // Put off with Not now: not offered again on launch for a day.
        val putOff = quiet && release?.version == prefs.getString(PREF_LATER, null) &&
            System.currentTimeMillis() - prefs.getLong(PREF_LATER_AT, 0L) < LATER_MS
        when {
            putOff -> Unit
            release != null && isNewer(release.version, BuildConfig.VERSION_NAME) -> offered = release
            !quiet -> note = "You're on the latest version (${BuildConfig.VERSION_NAME})."
        }
    }

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Pitwall/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(request).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (!r.isSuccessful) throw IOException("GitHub said ${r.code}")
            val body = r.body ?: throw IOException("Missing release metadata")
            val json = JSONObject(body.byteStream().use {
                ReleaseAssetValidation.readBoundedUtf8(it, MAX_RELEASE_METADATA_BYTES)
            })
            val assets = json.optJSONArray("assets") ?: return@withContext null
            val entries = (0 until assets.length()).map { assets.getJSONObject(it) }
            val tag = json.optString("tag_name")
            val version = tag.removePrefix("v")
            check(version.matches(Regex("1[.]0[.][0-9]+"))) { "Invalid Pitwall release version" }
            val apk = entries.singleOrNull { it.optString("name") == "pitwall-tv-$version.apk" }
                ?: return@withContext null
            val checksum = entries.singleOrNull { it.optString("name") == "SHA256SUMS" }
                ?: return@withContext null
            val size = apk.optLong("size")
            check(size in 1..MAX_APK_BYTES) { "APK length outside the update budget" }
            val assetUrl = apk.getString("browser_download_url")
            val checksumUrl = checksum.getString("browser_download_url")
            check(ReleaseAssetValidation.officialAssetUrl(BuildConfig.UPDATE_REPO, tag, apk.getString("name"), assetUrl) &&
                ReleaseAssetValidation.officialAssetUrl(BuildConfig.UPDATE_REPO, tag, "SHA256SUMS", checksumUrl)) {
                "Release assets are not from the installed Pitwall publisher"
            }
            Release(
                version = version,
                notes = json.optString("body").trim(),
                apkUrl = assetUrl,
                size = size,
                filename = apk.getString("name"),
                checksumsUrl = checksumUrl,
            )
        }
    }

    fun dismiss() {
        offered?.let { prefs.edit().putString(PREF_LATER, it.version).putLong(PREF_LATER_AT, System.currentTimeMillis()).apply() }
        offered = null
        note = null
    }

    /** Downloads the offered release and starts Android's installer on it. */
    fun install() {
        if (closed || progress != null || installJob?.isActive == true) return
        val release = offered ?: return
        progress = 0f
        checkJob?.cancel()
        installJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val apk = download(release)
                    try {
                        currentCoroutineContext().ensureActive()
                        startInstall(apk)
                    } finally {
                        apk.delete() // PackageInstaller has its own fsynced copy after commit.
                    }
                }
                offered = null
            } catch (cancelled: CancellationException) {
                if (!closed) progress = null
                throw cancelled
            } catch (error: Exception) {
                if (!closed) {
                    progress = null
                    note = "The update didn't download: ${safeError(error)}"
                }
            }
        }
    }

    private fun safeError(error: Throwable): String =
        DiagnosticRedaction.redact(error.message?.take(300) ?: "Update failed").take(200)

    private suspend fun download(release: Release): File {
        // Every approved release publishes a checksum beside its signed APK.
        // Fail closed on absent/corrupted APKs before invoking Android's installer.
        val checksums = http.newCall(Request.Builder().url(release.checksumsUrl)
            .header("User-Agent", "Pitwall/${BuildConfig.VERSION_NAME}").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Checksum manifest HTTP ${r.code}")
            (r.body ?: throw IOException("Missing checksum manifest")).byteStream().use {
                ReleaseAssetValidation.readBoundedUtf8(it, MAX_CHECKSUM_BYTES)
            }
        }
        val expected = ReleaseAssetValidation.expectedSha256(checksums, release.filename)
        val file = File.createTempFile("pitwall-update-", ".apk", context.cacheDir)
        try {
            http.newCall(Request.Builder().url(release.apkUrl).header("User-Agent", "Pitwall")
                .build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Update HTTP ${r.code}")
                val body = r.body ?: throw IOException("Empty update body")
                val total = release.size
                val declared = body.contentLength()
                check(declared < 0 || declared == total) { "APK length differs from release metadata" }
                check(total in 1..MAX_APK_BYTES) { "APK length outside the update budget" }
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        var lastReportedFraction = -1f
                        var lastReportedAtMs = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            received += n
                            check(received <= total && received <= MAX_APK_BYTES) { "APK exceeds the update budget" }
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                            val fraction = (received.toFloat() / total).coerceIn(0f, 1f)
                            val elapsedMs = android.os.SystemClock.elapsedRealtime()
                            if (UpdateProgressPolicy.shouldPublish(
                                    fraction, lastReportedFraction, elapsedMs - lastReportedAtMs
                                )) {
                                // Bound Compose snapshot updates and deliver on Main,
                                // never for every 64KB byte-buffer read on IO.
                                withContext(Dispatchers.Main.immediate) { progress = fraction }
                                lastReportedFraction = fraction
                                lastReportedAtMs = elapsedMs
                            }
                        }
                        check(received == total) { "Incomplete APK download" }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(expected == actual) { "APK SHA-256 does not match its release" }
                withContext(Dispatchers.Main.immediate) { progress = 1f }
            }
            verifyPackageIdentity(file)
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackageIdentity(apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IOException("Downloaded file is not an Android APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        check(candidate.packageName == context.packageName) { "APK package identity mismatch" }
        check(candidate.longVersionCode > installed.longVersionCode) { "APK is not a newer build" }
        val updateSigners = candidate.signingInfo?.apkContentsSigners.orEmpty()
        val installedSigners = installed.signingInfo?.apkContentsSigners.orEmpty()
        check(updateSigners.isNotEmpty() && installedSigners.isNotEmpty() &&
            updateSigners.contentEquals(installedSigners)) {
            "APK certificate does not match the installed Pitwall app"
        }
    }

    private suspend fun startInstall(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(id).use { session ->
                session.openWrite("apexview.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                        }
                    }
                    session.fsync(out)
                }
                currentCoroutineContext().ensureActive()
                val status = PendingIntent.getBroadcast(
                    context, id, Intent(statusAction).setClass(context, PitwallInstallStatusReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
                committed = true
            }
        } finally {
            if (!committed) runCatching { installer.abandonSession(id) }
        }
    }

    companion object {
        private const val PREF_CHECKED = "checkedAt"
        private const val PREF_LATER = "laterVersion"
        private const val PREF_LATER_AT = "laterAt"
        private const val LATER_MS = 24 * 3_600_000L
        private const val MAX_RELEASE_METADATA_BYTES = 1024 * 1024
        private const val MAX_CHECKSUM_BYTES = 16 * 1024
        private const val MAX_APK_BYTES = 250L * 1024 * 1024

        /** "0.36" > "0.35", "1.0" > "0.99", "0.35.1" > "0.35". */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val b = current.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** The update prompt: what's new, Update or Not now, then the download's progress. */
@Composable
fun UpdatePrompt(updater: Updater) {
    val release = updater.offered
    val note = updater.note
    if (release == null && note == null) return
    Dialog(
        onDismissRequest = { if (updater.progress == null) updater.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ink.surface)
                .padding(24.dp)
        ) {
            if (release == null) {
                Text(note.orEmpty(), style = Type.body)
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                    PrimaryButton("OK", onClick = updater::dismiss)
                }
                return@Column
            }
            Text("Pitwall TV ${release.version} is out", style = Type.title2)
            Text("You have ${BuildConfig.VERSION_NAME}.", style = Type.subhead, modifier = Modifier.padding(top = 4.dp))
            if (release.notes.isNotEmpty()) {
                Box(Modifier.padding(top = 16.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    Text(release.notes, style = Type.footnote.copy(color = Ink.primary))
                }
            }
            val progress = updater.progress
            if (progress != null) {
                Text(
                    if (progress < 1f) "Downloading… ${(progress * 100).toInt()}%" else "Opening the installer…",
                    style = Type.subhead,
                    modifier = Modifier.padding(top = 20.dp),
                )
                AppleSlider(value = progress, onValueChange = {}, modifier = Modifier.padding(top = 4.dp))
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End),
                ) {
                    SecondaryButton("Not now", onClick = updater::dismiss)
                    PrimaryButton("Update", onClick = updater::install)
                }
            }
            note?.let { Text(it, style = Type.footnote, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}
