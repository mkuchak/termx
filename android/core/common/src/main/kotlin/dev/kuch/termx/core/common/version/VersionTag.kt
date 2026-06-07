package dev.kuch.termx.core.common.version

/**
 * Pure-logic SemVer-ish comparator shared across modules.
 *
 * Two independent callers need the same MAJOR.MINOR.PATCH ordering:
 *
 *  - The APK self-updater (`:feature:updater`) compares the GitHub APK
 *    tag `"v1.1.16"` against [BuildConfig.VERSION_NAME] `"1.1.16"`.
 *  - The VPS companion (`termxd`) flow (`:core:data`, `:feature:servers`)
 *    compares the installed binary's `termx --version` output against the
 *    latest `termxd-v*` release tag (see the `companion*` helpers below).
 *
 * Comparison strategy: strip a leading `v` / `V`, split on `.`, drop
 * non-numeric trailing junk (`-rc1`, `+build`, etc.), compare each
 * component as Int with implicit-zero padding ("1.2" < "1.2.1"). This
 * is sufficient for our `MAJOR.MINOR.PATCH` release-it / GoReleaser
 * cadence; a full SemVer parser is overkill.
 *
 * No Android dependencies — keep it trivially unit-testable.
 */
object VersionTag {

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

    // ---------------------------------------------------------------------
    // Companion (`termxd`) version handling.
    //
    // The two strings arrive in DIFFERENT formats and must be normalised
    // before comparison:
    //   - Installed: `termx --version` prints `"termx version 0.1.4\n"`
    //     (cobra's default version template; bare MAJOR.MINOR.PATCH).
    //   - Latest:    the release tag is `"termxd-v0.1.4"` (prefixed).
    // ---------------------------------------------------------------------

    /**
     * Extracts the installed companion version from the raw `termx --version`
     * output (`"termx version 0.1.4\n"`).
     *
     * Defensive parse: take the LAST whitespace-delimited token (the version
     * sits at the end of cobra's template) and keep it only if it [normalise]s
     * to a digits-and-dots version. Anything else — an empty capture, the
     * `"termx (version unknown)"` fallback ([InstallCompanionUseCaseImpl]),
     * an error string, etc. — returns `null`, meaning UNKNOWN. Callers MUST
     * treat `null` as "offer (re)install", never as "up to date".
     */
    fun companionInstalledVersion(rawVersionOutput: String): String? {
        val lastToken = rawVersionOutput.trim().split(Regex("\\s+")).lastOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return normalise(lastToken)
    }

    /**
     * Extracts the latest companion version from a release [tag]
     * (`"termxd-v0.1.4"`). Strips a leading `termxd-` then reuses
     * [normalise] (which in turn strips the `v`). Returns `null` if the
     * remainder isn't a parseable version.
     */
    fun companionLatestVersion(tag: String): String? {
        val withoutPrefix = tag.trim().removePrefix("termxd-")
        return normalise(withoutPrefix)
    }

    /**
     * `true` iff the latest companion release is strictly newer than the
     * installed binary.
     *
     * Both arguments are the RAW strings the callers already hold:
     * [installedVersionOutput] is the `termx --version` capture and
     * [latestReleaseTag] is the `termxd-v*` tag.
     *
     * Returns `false` when the latest tag can't be parsed (nothing sane to
     * offer). When the INSTALLED version can't be parsed it is treated as the
     * zero version, so a valid latest release counts as an update — the
     * "version unknown" case should prompt a reinstall, never be silently
     * skipped.
     */
    fun isCompanionUpdateAvailable(
        installedVersionOutput: String,
        latestReleaseTag: String,
    ): Boolean {
        val latest = companionLatestVersion(latestReleaseTag) ?: return false
        // Empty string normalises to null -> treated as the zero version by
        // [compare], so an unparseable installed version still triggers the
        // offer rather than suppressing it.
        val installed = companionInstalledVersion(installedVersionOutput) ?: ""
        return isNewer(candidate = latest, installed = installed)
    }
}
