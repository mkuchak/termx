package dev.kuch.termx.libs.sshnative

/**
 * Build the `tmux new-session -A -s '<name>'` command termx runs on
 * connect when `Server.autoAttachTmux` is true.
 *
 * `-A` makes it attach-or-create, so the phone and a laptop can share
 * the same session without the order of connects mattering. Single
 * quotes are used so the shell doesn't split on whitespace — and we
 * reject names containing a literal `'` rather than try to escape it
 * (UI validation via [validateTmuxSessionName] covers the user path;
 * this require() is a last-line guard).
 */
fun tmuxAutoAttachCommand(sessionName: String): String {
    require(!sessionName.contains('\'')) { "tmux session names cannot contain '" }
    return "tmux new-session -A -s '$sessionName'"
}

/**
 * Validate a user-entered tmux session name.
 *
 * Reserved characters:
 *  - `'` — would break the single-quoted shell wrapper in
 *    [tmuxAutoAttachCommand].
 *  - `.`, `:` — reserved by tmux's own target syntax (`session:window.pane`).
 *  - any whitespace — tmux allows it but every CLI tool downstream will
 *    fight us; disallow for sanity.
 *
 * Returns a [Result] so the Add/Edit Server sheet (Task #22) can surface
 * the specific failure inline without try/catch.
 */
fun validateTmuxSessionName(name: String): Result<Unit> {
    if (name.isEmpty()) return Result.failure(IllegalArgumentException("Session name cannot be empty"))
    if (name.length > 64) return Result.failure(IllegalArgumentException("Session name too long (max 64)"))
    if (name.contains('\'')) return Result.failure(IllegalArgumentException("Single quotes are not allowed"))
    if (name.contains('.') || name.contains(':')) {
        return Result.failure(IllegalArgumentException("'.' and ':' are reserved by tmux"))
    }
    if (name.any { it.isWhitespace() }) {
        return Result.failure(IllegalArgumentException("Whitespace is not allowed"))
    }
    return Result.success(Unit)
}
