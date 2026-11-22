package app.apexview

import java.util.Locale

/** Track labels come from F1's actual Media3 manifest, never a hardcoded menu. */
internal data class CaptionTrackOption(val language: String, val label: String)

/** Pure selection logic shared by phone, Android TV and the local TV receiver. */
internal object CaptionPolicy {
    const val AUTO = "auto"
    private val languageTag = Regex("[a-z]{2,3}(-[a-z]{2,8})?")
    fun normalizeLanguage(input: String?): String {
        val value = input?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (value == AUTO || value.matches(languageTag)) value else AUTO
    }
    fun textLanguage(selected: String, commentary: String): String =
        normalizeLanguage(selected).takeUnless { it == AUTO }
            ?: normalizeLanguage(commentary).takeUnless { it == AUTO } ?: "en"

    /**
     * Prefer the composed owner's original audio master for protected playback.
     * The two playback modes route captions to one selected feed, not to every
     * onboard. Works with clear or Widevine source ownership unchanged.
     */
    fun <T> owner(
        feeds: List<T>, audible: T?, composedHost: T?, composedAudioMaster: T?,
        compositionActive: Boolean,
    ): T? = if (compositionActive) composedAudioMaster ?: composedHost
        else audible ?: feeds.firstOrNull()

    fun tracks(formats: List<Pair<String?, String?>>): List<CaptionTrackOption> =
        formats.map { (rawLanguage, rawLabel) ->
            val language = normalizeLanguage(rawLanguage).takeUnless { it == AUTO } ?: "und"
            val label = rawLabel?.trim()?.takeIf { it.isNotBlank() }?.take(64)
                ?: if (language == "und") "Language unspecified"
                else Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH)
                    .replaceFirstChar { it.titlecase(Locale.ENGLISH) }
            CaptionTrackOption(language, label)
        }.distinctBy { it.language }
}