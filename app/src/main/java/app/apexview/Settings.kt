package app.apexview

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Resolution preference applied to every active and newly opened feed. */
enum class VideoQuality(
    val label: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val targetHeight: Int,
) {
    AUTO("Auto", Int.MAX_VALUE, Int.MAX_VALUE, 0),
    P720("720p", 1280, 720, 720),
    P1080("1080p", 1920, 1080, 1080);

    companion object {
        fun fromPreference(value: String?): VideoQuality =
            values().firstOrNull { it.name == value } ?: AUTO
    }
}

/** Uses the same Leanback/UiMode checks as the viewer and remote-control path. */
internal fun isTvDevice(context: Context): Boolean =
    context.packageManager.hasSystemFeature("android.software.leanback") ||
        context.getSystemService(android.app.UiModeManager::class.java)?.currentModeType ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

/** Choices that live one level down, in Settings, and survive restarts. */
class Settings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("f1mv", Context.MODE_PRIVATE)

    var diagnosticLogging by mutableStateOf(DiagnosticLogs.isEnabled(appContext))
        private set

    fun updateDiagnosticLogging(value: Boolean) {
        diagnosticLogging = value
        DiagnosticLogs.setEnabled(appContext, value)
    }

    /** Which F1 TV player the app asks streams for. The web one is the tested default. */
    var platform by mutableStateOf(prefs.getString("platform", "WEB_DASH") ?: "WEB_DASH")
        private set
    /** Software DRM: what desktop Chrome gets, and what lets four feeds play at once. */
    var softwareDrm by mutableStateOf(prefs.getBoolean("softwareDrm", true))
        private set
    var subtitles by mutableStateOf(prefs.getBoolean("subtitles", false))
        private set
    var commentary by mutableStateOf(prefs.getString("commentary", "en") ?: "en")
        private set
    var showStats by mutableStateOf(prefs.getBoolean("showStats", false))
        private set
    var mapDriverSelection by mutableStateOf(prefs.getBoolean("mapDriverSelection", true))
        private set

    fun updateMapDriverSelection(value: Boolean) {
        mapDriverSelection = value
        prefs.edit().putBoolean("mapDriverSelection", value).apply()
    }

    /** Live races: a note near the end offering to vote for Driver of the Day. */
    var votePrompt by mutableStateOf(prefs.getBoolean("votePrompt", true))
        private set

    /** Video rendition cap shared by all feeds and retained for the next session. */
    var videoQuality by mutableStateOf(VideoQuality.fromPreference(prefs.getString("videoQuality", null)))
        private set

    /** The requested mode is retained even when a particular stream needs a safe fallback. */
    var decoderMode by mutableStateOf(
        DecoderMode.entries.firstOrNull { it.name == prefs.getString("decoderMode", null) } ?: DecoderMode.MULTIPLE
    )
        private set

    /** The onboards' speed, in [MPH] or [KMH]. */
    var speedUnit by mutableStateOf(prefs.getString("speedUnit", MPH) ?: MPH)
        private set

    fun updateVideoQuality(value: VideoQuality) {
        videoQuality = value
        prefs.edit().putString("videoQuality", value.name).apply()
    }

    fun updateDecoderMode(value: DecoderMode) { decoderMode = value; prefs.edit().putString("decoderMode", value.name).apply() }

    fun updatePlatform(value: String) { platform = value; prefs.edit().putString("platform", value).apply() }
    fun updateSoftwareDrm(value: Boolean) { softwareDrm = value; prefs.edit().putBoolean("softwareDrm", value).apply() }
    fun updateSubtitles(value: Boolean) { subtitles = value; prefs.edit().putBoolean("subtitles", value).apply() }
    fun updateCommentary(value: String) { commentary = value; prefs.edit().putString("commentary", value).apply() }
    fun updateShowStats(value: Boolean) { showStats = value; prefs.edit().putBoolean("showStats", value).apply() }
    fun updateVotePrompt(value: Boolean) { votePrompt = value; prefs.edit().putBoolean("votePrompt", value).apply() }
    fun updateSpeedUnit(value: String) { speedUnit = value; prefs.edit().putString("speedUnit", value).apply() }

    companion object {
        const val MPH = "mph"
        const val KMH = "kmh"

        /** F1 TV's commentary languages, by ISO 639-1 code. */
        val COMMENTARY = listOf(
            "en" to "English",
            "fr" to "Français",
            "de" to "Deutsch",
            "es" to "Español",
            "nl" to "Nederlands",
            "pt" to "Português",
        )
    }
}
