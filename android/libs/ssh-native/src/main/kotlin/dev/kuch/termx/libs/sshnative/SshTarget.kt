package dev.kuch.termx.libs.sshnative

/**
 * Addressing for an SSH endpoint.
 *
 * @param host DNS name or IP address
 * @param port TCP port of the sshd (default 22)
 * @param username remote user
 * @param knownHostsPath absolute path to the known_hosts file used by this
 *   client. Typically `context.filesDir.absolutePath + "/known_hosts"`. The
 *   file is created on first use if it does not exist.
 */
data class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
    val knownHostsPath: String,
)
