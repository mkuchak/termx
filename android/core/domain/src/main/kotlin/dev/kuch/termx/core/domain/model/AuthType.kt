package dev.kuch.termx.core.domain.model

/**
 * How a [Server] authenticates against the remote host.
 *
 * [KEY] resolves to a [KeyPair] stored in the Keystore-encrypted vault (Task #20).
 * [PASSWORD] means the user types the password each time they connect — the
 * value is held in view-model memory for the current flow and never written to
 * Room. End-to-end password auth is wired for terminal connect (Phase 1) and
 * the companion install wizard; persisted/biometric-gated password storage
 * remains a follow-up so repeated connects still prompt.
 */
enum class AuthType { KEY, PASSWORD }
