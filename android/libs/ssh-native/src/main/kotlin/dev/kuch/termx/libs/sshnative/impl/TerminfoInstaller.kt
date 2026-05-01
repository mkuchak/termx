package dev.kuch.termx.libs.sshnative.impl

import android.content.Context
import java.io.File

/**
 * One-shot extractor for the bundled minimal terminfo database.
 *
 * mosh-client links against ncurses; ncurses resolves TERM by looking
 * up `$TERMINFO/<first-letter>/<name>` for compiled terminfo entries.
 * Stock Android has no `/usr/share/terminfo/` tree at all, so without
 * this step `setupterm()` fails in-proc with `"Cannot find termcap
 * entry"` and mosh-client exits before opening its UDP socket — the
 * user sees an instant "Disconnected" with no diagnostics.
 *
 * The assets tree at `src/main/assets/terminfo/` ships exactly four
 * compiled entries: `x/xterm-256color` (what we request in the PTY),
 * `x/xterm` (fallback), `v/vt100` (fallback for ancient servers),
 * `d/dumb` (last-resort). Total ~10 KB uncompressed.
 *
 * First call copies the whole tree into `context.filesDir/terminfo/`
 * and writes a version marker; subsequent calls no-op after a single
 * `File.exists()` check.
 */
internal object TerminfoInstaller {

    /** Bump when the bundled terminfo tree changes so we re-extract. */
    private const val MARKER_NAME = ".installed-v1"
    private const val ASSET_ROOT = "terminfo"

    /**
     * Copies the bundled terminfo tree from this module's assets into
     * the app's private `filesDir` on first call. Idempotent —
     * subsequent calls are near-free (only the marker file is read).
     *
     * Returns the root directory suitable for setting as the `TERMINFO`
     * environment variable on the mosh-client child process.
     */
    fun ensureInstalled(context: Context): File {
        val target = File(context.filesDir, ASSET_ROOT)
        val marker = File(target, MARKER_NAME)
        if (marker.exists()) return target
        target.mkdirs()
        val am = context.assets
        am.list(ASSET_ROOT)?.forEach { dir ->
            val subDir = File(target, dir).apply { mkdirs() }
            am.list("$ASSET_ROOT/$dir")?.forEach { entry ->
                am.open("$ASSET_ROOT/$dir/$entry").use { input ->
                    File(subDir, entry).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        marker.writeText("1")
        return target
    }
}
