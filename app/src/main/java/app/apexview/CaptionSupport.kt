package app.apexview

import java.util.Locale

/** Track labels come from F1's actual Media3 manifest, never a hardcoded menu. */
data class CaptionTrackOption(val language: String, val label: String)

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

    private val castCaptionId = Regex("pitwall-text-([0-5])-[0-3]")
    fun castProgramme(formatId: String?): Int? =
        formatId?.let { castCaptionId.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Explicit language choices never borrow another programme or language. */
    fun preferredIndex(selected: String, commentary: String, languages: List<String?>, defaults: List<Boolean>): Int? {
        val language = textLanguage(selected, commentary)
        val normalized = languages.map(::normalizeLanguage)
        val exact = normalized.indexOf(language)
        if (exact >= 0) return exact
        val base = normalized.indexOfFirst { it.substringBefore('-') == language.substringBefore('-') }
        if (base >= 0) return base
        if (normalizeLanguage(selected) != AUTO) return null
        return defaults.indexOf(true).takeIf { it in languages.indices }
            ?: normalized.indexOf("und").takeIf { it >= 0 }
            ?: languages.indices.firstOrNull()
    }

    /**
     * Prefer the composed owner's original audio master for protected playback.
     * The two playback modes route captions to one selected feed, not to every
     * onboard. Works with clear or Widevine source ownership unchanged.
     */
    fun <T> owner(
        feeds: List<T>, audible: T?, composedHost: T?, composedAudioMaster: T?,
        compositionActive: Boolean,
        clearArchiveComposed: Boolean = false,
    ): T? = when {
        // Clear HEVC merges the source's native audio+TEXT into its one
        // composed ExoPlayer. The original audio tile no longer owns cues.
        compositionActive && clearArchiveComposed -> composedHost
        // Protected rolling HEVC replaces only picture playback; the
        // authorized audio owner continues to own its source text tracks.
        compositionActive -> composedAudioMaster ?: composedHost
        else -> audible ?: feeds.firstOrNull()
    }

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