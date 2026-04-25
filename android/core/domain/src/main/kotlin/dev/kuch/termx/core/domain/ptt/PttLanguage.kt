package dev.kuch.termx.core.domain.ptt

/**
 * Push-to-talk language catalogue. The seven locales the app exposes in
 * the Settings dropdowns and pipes through Gemini's prompt as the
 * source / target language. Mirrors the list shipped by
 * github.com/mkuchak/push-to-talk so users have parity between the
 * Electron desktop app and termx.
 *
 * Stored on disk as a BCP-47-ish locale code (e.g. "pt-BR"); rendered to
 * the user via [displayLabel] which collapses the dictionary value to a
 * "Language (Country)" form ("Portuguese (BR)") for the dropdown rows
 * and to [fullName] for prompt interpolation ("Brazilian Portuguese").
 */
object PttLanguage {

    /** Locale codes in display order, English first. */
    val codes: List<String> = listOf(
        "en-US",
        "pt-BR",
        "es-CO",
        "es-ES",
        "fr-FR",
        "de-DE",
        "hi-IN",
    )

    /**
     * Locale code → full English name. Used inside the Gemini prompt so
     * the model has both the code and the human-readable name when
     * deciding which language to transcribe / translate into.
     */
    val fullName: Map<String, String> = mapOf(
        "en-US" to "American English",
        "pt-BR" to "Brazilian Portuguese",
        "es-CO" to "Colombian Spanish",
        "es-ES" to "Spanish",
        "fr-FR" to "French",
        "de-DE" to "German",
        "hi-IN" to "Hindi",
    )

    /** Default language used for both source and target on first install. */
    const val DEFAULT_CODE: String = "en-US"

    /**
     * Render a locale code as "Language (Country)" — e.g. "en-US" →
     * "English (US)", "pt-BR" → "Portuguese (BR)". Falls back to the
     * raw code for unknown entries so the UI stays usable if a stale
     * pref ever points at a removed locale.
     */
    fun displayLabel(code: String): String {
        val name = fullName[code] ?: return code
        val language = name.substringAfterLast(' ')
        val country = code.substringAfter('-', missingDelimiterValue = code)
        return "$language ($country)"
    }

    /**
     * Bare-language token used in the translate prompt to keep the
     * sentence short — e.g. "American English" → "English",
     * "Brazilian Portuguese" → "Portuguese". Falls back to the full
     * name when the dictionary value is a single word.
     */
    fun shortName(code: String): String {
        val name = fullName[code] ?: return code
        return name.substringAfterLast(' ')
    }

    /**
     * Sanitise a stored value before use: an unknown code (renamed
     * locale, corrupted pref) collapses to [DEFAULT_CODE] so the rest
     * of the pipeline can assume the language is always renderable.
     */
    fun normalise(code: String): String =
        if (code in fullName) code else DEFAULT_CODE
}
