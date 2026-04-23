package dev.kuch.termx.libs.sshnative

/**
 * Module-local exception hierarchy. Feature modules must catch these
 * instead of sshj's checked exceptions — the impl layer translates every
 * sshj failure into one of these subclasses, so upstream never imports
 * `net.schmizz.*`.
 */
sealed class SshException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Password rejected / key refused / no more auth methods. */
    class AuthFailed(message: String = "Authentication failed", cause: Throwable? = null) :
        SshException(message, cause)

    /** DNS, TCP, or transport-level connect failure. */
    class HostUnreachable(message: String = "Host unreachable", cause: Throwable? = null) :
        SshException(message, cause)

    /** The server's host key does not match the pinned known_hosts entry. */
    class HostKeyMismatch(message: String = "Host key mismatch", cause: Throwable? = null) :
        SshException(message, cause)

    /** Any operation exceeded its deadline. */
    class TimedOut(message: String = "Operation timed out", cause: Throwable? = null) :
        SshException(message, cause)

    /** Channel was closed mid-operation (by either side). */
    class ChannelClosed(message: String = "Channel closed", cause: Throwable? = null) :
        SshException(message, cause)

    /** Anything the impl layer could not classify. */
    class Unknown(message: String = "Unknown SSH error", cause: Throwable? = null) :
        SshException(message, cause)
}
