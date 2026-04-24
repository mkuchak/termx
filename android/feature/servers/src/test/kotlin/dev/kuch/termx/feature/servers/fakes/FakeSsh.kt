package dev.kuch.termx.feature.servers.fakes

import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshException
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget

/**
 * Minimal [SshClient] override used by viewmodel tests that want to
 * exercise the SshException to user-facing string mapping in
 * [dev.kuch.termx.feature.servers.AddEditServerViewModel.testConnection].
 *
 * Set [connectException] to any [SshException] subclass (or any other
 * [Throwable]) to drive the VM's error branches; leave it null to make
 * `connect` throw `IllegalStateException("unexpected test call")` since
 * the happy-path tests don't need a real SshSession.
 */
class FakeSshClient(
    var connectException: Throwable? = null,
) : SshClient() {

    override suspend fun connect(
        target: SshTarget,
        auth: SshAuth,
        timeoutMillis: Long,
    ): SshSession {
        val err = connectException
        if (err != null) throw err
        error("FakeSshClient: no SshSession configured — set connectException for error-path tests")
    }
}
