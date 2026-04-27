package dev.kuch.termx.libs.sshnative.impl

import android.content.Context
import android.util.Log
import dev.kuch.termx.libs.sshnative.MoshSession
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mosh-client orchestrator.
 *
 * 1. Opens a short-lived SSH session (reused [SshClient]) and runs
 *    `mosh-server new -s -c 256 -i <bindIp> -p <portRange>`.
 * 2. Drains the exec's **stdout AND stderr** for the canonical
 *    `MOSH CONNECT <port> <key>` line — see https://mosh.org. Stderr
 *    is also scanned because some mosh-server builds (and Termux's,
 *    notably) print the line there. Anything else (banner chatter,
 *    usage output from an old mosh, errors) is discarded.
 * 3. Closes the exec + SSH session — mosh-server double-forks and
 *    detaches, so the UDP listener stays up.
 * 4. Hands `(host, port, key)` to [SspMoshSessionImpl], the pure-Kotlin
 *    SSP transport adapter (vendored as the `:mosh-transport` composite
 *    build). v1.1.21 retired the `libmoshclient.so` fork+exec path; this
 *    runs entirely on the JVM.
 *
 * Returns `null` from [tryConnect] only if the SSH-side handshake
 * doesn't produce a `MOSH CONNECT` line within the timeout. Once the
 * handshake lands, the transport never fails synchronously — UDP /
 * decryption / session-dead errors arrive via
 * `MoshSession.diagnostic` so the caller can surface them via
 * `MoshExitMessage`.
 */
internal class MoshClientImpl(
    private val context: Context,
    private val sshClient: SshClient,
) {

    suspend fun tryConnect(
        target: SshTarget,
        auth: SshAuth,
        bindIp: String,
        portRange: String,
        handshakeTimeoutMs: Long,
    ): MoshSession? {
        val handshake = withTimeoutOrNull(handshakeTimeoutMs) {
            handshake(target, auth, bindIp, portRange)
        } ?: return null

        return openSession(target.host, handshake.port, handshake.key)
    }

    /**
     * Drain `mosh-server new` for the first `MOSH CONNECT` line.
     *
     * Merges stdout + stderr into a single regex-match buffer because
     * different mosh-server builds emit the line on different streams
     * (Termux's writes to stderr; upstream's to stdout); without
     * draining both, the handshake silently times out on the wrong
     * stream. Throwable-based short-circuit keeps the flow `collect`
     * loop terminal.
     */
    private suspend fun handshake(
        target: SshTarget,
        auth: SshAuth,
        bindIp: String,
        portRange: String,
    ): Handshake? = withContext(Dispatchers.IO) {
        val session: SshSession = sshClient.connect(target, auth)
        try {
            val cmd = "mosh-server new -s -c 256 -i $bindIp -p $portRange"
            session.openExec(cmd).use { exec ->
                val accumulator = StringBuilder()
                var handshake: Handshake? = null
                try {
                    merge(exec.stdout, exec.stderr).collect { chunk ->
                        accumulator.append(String(chunk, Charsets.UTF_8))
                        val match = MOSH_CONNECT_REGEX.find(accumulator)
                        if (match != null) {
                            val port = match.groupValues[1].toIntOrNull()
                            val key = match.groupValues[2].trim()
                            if (port != null && key.isNotEmpty()) {
                                handshake = Handshake(port, key)
                                throw HandshakeFound
                            }
                        }
                    }
                } catch (_: HandshakeFoundException) {
                    // expected: signals we saw the MOSH CONNECT line
                }
                handshake
            }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "mosh-server handshake failed", t)
            null
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * Open the SSP transport adapter against the address mosh-server
     * just published. v1.1.21 supersedes the v1.1.20
     * `NativePty.spawn(libmoshclient.so, …)` path: same return type,
     * but no fork/exec, no native binary dependencies, no per-device
     * bionic linker SIGSEGVs. Initial 80×24 geometry is reset by the
     * emulator's first `resize` callback.
     */
    private fun openSession(host: String, port: Int, key: String): MoshSession =
        SspMoshSessionImpl(
            host = host,
            port = port,
            keyBase64 = key,
            initialCols = INITIAL_COLS,
            initialRows = INITIAL_ROWS,
        )

    private data class Handshake(val port: Int, val key: String)

    /** Private marker to short-circuit out of the exec stdout/stderr collect. */
    private object HandshakeFoundException : RuntimeException() {
        private fun readResolve(): Any = HandshakeFoundException
    }

    /** Alias used at the throw site to keep the name grammatical. */
    private val HandshakeFound: HandshakeFoundException get() = HandshakeFoundException

    private companion object {
        const val LOG_TAG = "MoshClientImpl"
        val MOSH_CONNECT_REGEX = Regex("""MOSH CONNECT (\d+) (\S+)""")

        // Reasonable default geometry for the initial transport
        // resize. The composable-side emulator calls resize() with
        // the real cell count as soon as the AndroidView attaches, so
        // these are just "something sensible" for the ~1 frame before
        // that happens.
        const val INITIAL_COLS = 80
        const val INITIAL_ROWS = 24
    }
}
