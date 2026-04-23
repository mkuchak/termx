package dev.kuch.termx.core.domain.model

/**
 * How a [Server] authenticates against the remote host.
 *
 * [KEY] resolves to a [KeyPair] stored in the Keystore-encrypted vault (Task #20).
 * [PASSWORD] is currently a placeholder — password handling is gated behind the
 * vault work and is not yet wired end-to-end.
 */
enum class AuthType { KEY, PASSWORD }
