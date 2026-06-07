package dev.kuch.termx.feature.terminal

/**
 * Builds the remote command line that auto-runs a user's startup command
 * (e.g. a multiplexer like herdr/tmux) on connect, or null when disabled/blank.
 *
 * Wrapped in a login shell (`$SHELL -lc`) so the remote login PATH loads:
 * sshd and mosh-server both run the command non-login/non-interactive, so a
 * bare `herdr` in ~/.cargo/bin / ~/.local/bin would otherwise be "command not
 * found". `${SHELL:-/bin/sh}` is robust if $SHELL is unset in the exec env.
 * `|| exec $SHELL -l` drops the user to a login shell on FAILURE (non-zero) so
 * a missing/typo'd command is debuggable instead of a silent disconnect; a
 * clean exit still tears the session down. The same string is used verbatim on
 * both transports (SSH execs it; the mosh path appends it after
 * `mosh-server new … --`).
 */
internal fun buildStartupCommand(enabled: Boolean, rawCommand: String): String? {
    if (!enabled) return null
    val cmd = rawCommand.trim()
    if (cmd.isEmpty()) return null
    val inner = "$cmd || exec \$SHELL -l"
    val escaped = inner.replace("'", "'\\''") // POSIX single-quote escaping
    return "\${SHELL:-/bin/sh} -lc '$escaped'"
}
