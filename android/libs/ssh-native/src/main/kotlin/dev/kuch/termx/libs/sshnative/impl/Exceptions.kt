package dev.kuch.termx.libs.sshnative.impl

import dev.kuch.termx.libs.sshnative.SshException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Translate an sshj / JDK I/O throwable into the module's public exception
 * hierarchy. Keeps `net.schmizz.*` off the public API surface.
 */
internal fun Throwable.toSshException(): SshException {
    if (this is SshException) return this
    return when (this) {
        is UserAuthException -> SshException.AuthFailed(message ?: "Authentication failed", this)
        is SocketTimeoutException -> SshException.TimedOut(message ?: "Timed out", this)
        is UnknownHostException,
        is NoRouteToHostException,
        is ConnectException ->
            SshException.HostUnreachable(message ?: "Host unreachable", this)
        is TransportException -> {
            val msg = message.orEmpty().lowercase()
            when {
                "host key" in msg || "hostkey" in msg ->
                    SshException.HostKeyMismatch(message ?: "Host key mismatch", this)
                "timeout" in msg ->
                    SshException.TimedOut(message ?: "Timed out", this)
                else ->
                    SshException.Unknown(message ?: "Transport error", this)
            }
        }
        else -> SshException.Unknown(message ?: "SSH error", this)
    }
}
