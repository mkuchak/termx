package dev.kuch.termx.feature.updater

/**
 * Pure-logic version-tag handling for the updater. Tags from the
 * release pipeline look like `"v1.1.16"`, while [BuildConfig.VERSION_NAME]
 * is `"1.1.16"` (no `v` prefix, set from `version.json`'s `versionName`).
 *
 * Comparison strategy: strip a leading `v` / `V`, split on `.`, drop
 * non-numeric trailing junk (`-rc1`, `+build`, etc.), compare each
 * component as Int with implicit-zero padding ("1.2" < "1.2.1"). This
 * is sufficient for our `MAJOR.MINOR.PATCH` release-it cadence; a
 * full SemVer parser is overkill.
 */
internal object VersionTag {

    /**
     * Strip a leading `v` and any trailing pre-release / build
     * metadata that Kotlin's `Int.toIntOrNull` would reject. Returns
     * the cleaned dotted version or `null` if the input is unusable.
     */
    fun normalise(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withoutPrefix = if (trimmed[0] == 'v' || trimmed[0] == 'V') trimmed.substring(1) else trimmed
        // Drop SemVer suffix at the first '-' or '+' (e.g. "1.2.3-rc1+meta").
        val core = withoutPrefix.substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        // Validate every component is a non-negative integer.
        val parts = core.split('.')
        if (parts.any { it.toIntOrNull()?.let { n -> n < 0 } != false }) return null
        return core
    }

    /**
     * Returns positive if [a] is newer than [b], negative if older,
     * zero if equal. Unparseable inputs sort BEFORE everything (treated
     * as the zero version) so a malformed `BuildConfig.VERSION_NAME`
     * still triggers an update prompt rather than silently swallowing it.
     */
    fun compare(a: String, b: String): Int {
        val left = normalise(a)?.split('.')?.map { it.toInt() } ?: emptyList()
        val right = normalise(b)?.split('.')?.map { it.toInt() } ?: emptyList()
        val length = maxOf(left.size, right.size)
        for (i in 0 until length) {
            val l = left.getOrNull(i) ?: 0
            val r = right.getOrNull(i) ?: 0
            if (l != r) return l - r
        }
        return 0
    }

    /** `true` iff [candidate] is strictly newer than [installed]. */
    fun isNewer(candidate: String, installed: String): Boolean =
        compare(candidate, installed) > 0
}
