package dev.kuch.termx.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards on [buildStartupCommand] — the pure builder that turns a
 * server's `startupCommandEnabled` + `startupCommand` into the exact
 * remote command line execed on connect (SSH) / appended after
 * `mosh-server new … --` (mosh). The produced string is load-bearing:
 * it's a literal shell command that runs verbatim on the remote.
 *
 *  - Disabled or blank → null, so the caller falls back to a plain
 *    login shell with no `-c` wrapper at all.
 *  - The wrapper is `${SHELL:-/bin/sh} -lc '<cmd> || exec $SHELL -l'`.
 *    The OUTER `${SHELL:-/bin/sh}` is expanded by the exec/bootstrap
 *    shell; the INNER `$SHELL` stays literal inside single quotes and is
 *    expanded by the remote login shell at runtime — the two are NOT the
 *    same expansion and the builder must preserve the distinction.
 *  - Single quotes in the user's command are POSIX-escaped (`'\''`) so
 *    they don't prematurely close the wrapping quote.
 *  - `$`-bearing commands stay literal inside the single quotes (no
 *    escaping needed — single quotes already suppress expansion at this
 *    layer), so e.g. `echo $HOME` reaches the remote unmangled.
 */
class StartupCommandTest {

    @Test fun `disabled returns null even with a command`() {
        assertNull(buildStartupCommand(enabled = false, rawCommand = "herdr"))
    }

    @Test fun `enabled but blank command returns null`() {
        assertNull(buildStartupCommand(enabled = true, rawCommand = ""))
    }

    @Test fun `enabled but whitespace-only command returns null`() {
        assertNull(buildStartupCommand(enabled = true, rawCommand = "   \t  "))
    }

    @Test fun `plain command is wrapped in a login shell with failure fallback`() {
        assertEquals(
            "\${SHELL:-/bin/sh} -lc 'herdr || exec \$SHELL -l'",
            buildStartupCommand(enabled = true, rawCommand = "herdr"),
        )
    }

    @Test fun `surrounding whitespace is trimmed before wrapping`() {
        assertEquals(
            "\${SHELL:-/bin/sh} -lc 'herdr || exec \$SHELL -l'",
            buildStartupCommand(enabled = true, rawCommand = "  herdr  "),
        )
    }

    @Test fun `single quote in the command is POSIX-escaped`() {
        // echo 'hi'  ->  the two single quotes each become '\'' so they
        // don't close the wrapping quote prematurely.
        assertEquals(
            "\${SHELL:-/bin/sh} -lc 'echo '\\''hi'\\'' || exec \$SHELL -l'",
            buildStartupCommand(enabled = true, rawCommand = "echo 'hi'"),
        )
    }

    @Test fun `dollar sign in the command stays literal inside single quotes`() {
        // echo $HOME — single quotes already suppress expansion at the
        // bootstrap layer, so the $HOME must survive verbatim to be
        // expanded by the remote login shell, not the local builder.
        assertEquals(
            "\${SHELL:-/bin/sh} -lc 'echo \$HOME || exec \$SHELL -l'",
            buildStartupCommand(enabled = true, rawCommand = "echo \$HOME"),
        )
    }
}
