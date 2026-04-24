package dev.kuch.termx.core.data.remote

import dev.kuch.termx.core.data.network.TermxReleaseFetcher
import dev.kuch.termx.core.data.di.KnownHostsPath
import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.data.vault.VaultLockedException
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import dev.kuch.termx.core.domain.usecase.InstallCompanionUseCase
import dev.kuch.termx.core.domain.usecase.InstallCompanionUseCase.Stage
import dev.kuch.termx.core.domain.usecase.InstallStep3State
import dev.kuch.termx.libs.sshnative.ExecChannel
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Orchestrates the wizard-driven termxd install flow.
 *
 * Each [run] invocation opens a short-lived [SshSession] for the stage, then
 * closes it. We don't piggyback on `TmuxSessionRepositoryImpl`'s cache because
 *
 *  1. the wizard runs before the server has been persisted's `autoAttachTmux`
 *     path fires, so nothing has pre-warmed a session for this server;
 *  2. reusing the tmux cache would require exposing it as a public API, and
 *     the install flow is at most three round-trips per stage — a fresh
 *     session is cheap enough.
 *
 *  The impl is public + non-`internal` so Hilt's `@Binds` can resolve the
 *  domain interface against it (mirrors the fix documented in commit 074cb68).
 */
@Singleton
class InstallCompanionUseCaseImpl @Inject constructor(
    @KnownHostsPath private val knownHostsPath: String,
    private val serverRepository: ServerRepository,
    private val keyPairRepository: KeyPairRepository,
    private val secretVault: SecretVault,
    private val sshClient: SshClient,
    private val releaseFetcher: TermxReleaseFetcher,
) : InstallCompanionUseCase {

    override fun run(
        serverId: UUID,
        stage: Stage,
        context: InstallCompanionUseCase.Context,
    ): Flow<InstallStep3State> = flow {
        when (stage) {
            Stage.Detect -> runDetect(serverId, context.passwordOverride)
            Stage.Preview -> runPreview(serverId, context.downloadUrl, context.passwordOverride)
            Stage.Install -> runInstall(serverId, context.passwordOverride)
        }
    }.flowOn(Dispatchers.IO)

    // sshj's `client.timeout` is the transport-level idle read timeout — a
    // channel read that sits silent longer than this raises SocketTimeoutException
    // and tears the session down. Detect is a couple of short execs (~ms),
    // Preview curls a binary and runs `termx install --dry-run` (seconds), but
    // Install runs `termx install --install-deps` which shells out to apt-get
    // and routinely sits silent for minutes. We tune per stage to avoid
    // aborting a successful install mid-flight; DNS/handshake uses the same
    // budget which is harmless.
    private val detectTimeoutMillis = 15_000L
    private val previewTimeoutMillis = 120_000L
    private val installTimeoutMillis = 600_000L

    // Single SFTP operation (stat/exists/read/write) ceiling. Defensive:
    // sshj blocks on the subsystem's socket, and Thread.interrupt doesn't
    // always propagate from coroutine cancellation.
    private companion object {
        const val SFTP_OP_TIMEOUT_MS = 15_000L
    }

    // --- Stage: detect ------------------------------------------------------

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallStep3State>.runDetect(
        serverId: UUID,
        passwordOverride: String?,
    ) {
        emit(InstallStep3State.Detecting)
        val session = try {
            openSession(serverId, passwordOverride, detectTimeoutMillis)
        } catch (t: Throwable) {
            emit(InstallStep3State.Error(describe(t)))
            return
        }
        try {
            // Resolve `termx` via PATH *and* the canonical install location.
            // `which` returns 1 when the binary isn't on the shell's PATH
            // (non-login shell over sshj), so we explicitly check
            // `$HOME/.local/bin/termx` which is where termxd self-installs.
            val pathLine = execCapture(
                session,
                "command -v termx 2>/dev/null || " +
                    "([ -x \"\$HOME/.local/bin/termx\" ] && printf '%s' \"\$HOME/.local/bin/termx\") || true",
            ).trim()

            if (pathLine.isNotBlank()) {
                val quoted = shellQuote(pathLine)
                val version = execCapture(session, "$quoted --version 2>/dev/null || true")
                    .trim()
                    .ifBlank { "termx (version unknown)" }
                emit(InstallStep3State.AlreadyInstalled(version))
                return
            }

            val archRaw = execCapture(session, "uname -m").trim()
            val arch = normalizeArch(archRaw)
            if (arch == null) {
                emit(InstallStep3State.Error("Unsupported architecture: $archRaw"))
                return
            }

            emit(InstallStep3State.FetchingRelease)
            val rel = try {
                releaseFetcher.fetchLatest()
            } catch (t: Throwable) {
                emit(InstallStep3State.Error("GitHub release fetch failed: ${t.message ?: t.javaClass.simpleName}"))
                return
            }
            val url = rel.assetForArch(arch)
            if (url.isNullOrBlank()) {
                emit(InstallStep3State.Error("No asset for architecture $arch in release ${rel.tag}"))
                return
            }
            emit(InstallStep3State.ReadyToDownload(arch = arch, downloadUrl = url, releaseTag = rel.tag))
        } finally {
            runCatching { session.close() }
        }
    }

    // --- Stage: preview -----------------------------------------------------

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallStep3State>.runPreview(
        serverId: UUID,
        downloadUrl: String?,
        passwordOverride: String?,
    ) {
        if (downloadUrl.isNullOrBlank()) {
            emit(InstallStep3State.Error("Missing download URL"))
            return
        }
        emit(InstallStep3State.Downloading("Downloading ${downloadUrl.substringAfterLast('/')}..."))

        val session = try {
            openSession(serverId, passwordOverride, previewTimeoutMillis)
        } catch (t: Throwable) {
            emit(InstallStep3State.Error(describe(t)))
            return
        }
        try {
            val quotedUrl = shellQuote(downloadUrl)
            // Download, then auto-extract if the asset is a tarball/zip so
            // the final `/tmp/termx` is always an executable binary.
            val fetchCmd = """
                set -e
                rm -rf /tmp/termx-install && mkdir -p /tmp/termx-install
                cd /tmp/termx-install
                curl -fsSL $quotedUrl -o asset
                case "$quotedUrl" in
                    *.tar.gz|*.tgz) tar -xzf asset ;;
                    *.zip) unzip -q asset ;;
                    *) cp asset termx ;;
                esac
                bin=${'$'}(find . -maxdepth 3 -type f -name termx -perm -u+x | head -n 1)
                if [ -z "${'$'}bin" ]; then bin=${'$'}(find . -maxdepth 3 -type f -name termx | head -n 1); fi
                if [ -z "${'$'}bin" ]; then echo "no termx binary in archive" >&2; exit 1; fi
                install -m 0755 "${'$'}bin" /tmp/termx
            """.trimIndent()
            val fetchExit = execDiscard(session, fetchCmd)
            if (fetchExit != 0) {
                emit(InstallStep3State.Error("Download failed (exit $fetchExit). Check the VPS network and try again."))
                return
            }

            emit(InstallStep3State.Downloading("Running termx install --dry-run..."))
            val dryRunJson = execCapture(session, "/tmp/termx install --dry-run 2>/dev/null")
            val actions = DryRunParser.parse(dryRunJson)
            if (actions.isEmpty()) {
                emit(
                    InstallStep3State.Error(
                        "Dry-run returned no changes — the binary may be incompatible.",
                        log = dryRunJson.lines().take(40),
                    ),
                )
                return
            }
            emit(InstallStep3State.PreviewingDiff(actions))
        } finally {
            runCatching { session.close() }
        }
    }

    // --- Stage: install -----------------------------------------------------

    private suspend fun kotlinx.coroutines.flow.FlowCollector<InstallStep3State>.runInstall(
        serverId: UUID,
        passwordOverride: String?,
    ) {
        val log = mutableListOf<String>()
        emit(InstallStep3State.Installing(log.toList()))

        val session = try {
            openSession(serverId, passwordOverride, installTimeoutMillis)
        } catch (t: Throwable) {
            emit(InstallStep3State.Error(describe(t), log = log.toList()))
            return
        }
        try {
            val exec = session.openExec(
                "set -eo pipefail; /tmp/termx install --install-deps 2>&1",
            )
            val exit = try {
                collectStreamLines(exec) { line ->
                    log += line
                    emit(InstallStep3State.Installing(log.toList()))
                }
            } finally {
                runCatching { exec.close() }
            }
            if (exit != 0) {
                emit(InstallStep3State.Error("termx install exited with $exit", log = log.toList()))
                return
            }

            // Sanity check: the install_binary step should have produced
            // ~/.termx/events.ndjson. If missing, something went wrong
            // despite a zero exit code.
            //
            // Wrap the SFTP `exists` call in a withTimeout — sshj's blocking
            // I/O doesn't always respond to coroutine cancellation, so a sick
            // server mid-SFTP would otherwise stall the wizard indefinitely.
            val eventsCheck = try {
                val sftp = session.openSftp()
                try {
                    val home = execCapture(session, "printf %s \"\$HOME\"").trim()
                    val candidate = if (home.isNotBlank()) "$home/.termx/events.ndjson" else "~/.termx/events.ndjson"
                    kotlinx.coroutines.withTimeout(SFTP_OP_TIMEOUT_MS) {
                        sftp.exists(candidate)
                    }
                } finally {
                    runCatching { sftp.close() }
                }
            } catch (t: Throwable) {
                log += "verification failed: ${t.message ?: t.javaClass.simpleName}"
                false
            }
            if (!eventsCheck) {
                emit(
                    InstallStep3State.Error(
                        "Install completed but ~/.termx/events.ndjson was not found. Re-run install.",
                        log = log.toList(),
                    ),
                )
                return
            }

            val server = serverRepository.getById(serverId)
            if (server != null) {
                serverRepository.upsert(server.copy(companionInstalled = true))
            }
            emit(InstallStep3State.Success)
        } finally {
            runCatching { session.close() }
        }
    }

    // --- helpers ------------------------------------------------------------

    private suspend fun execCapture(session: SshSession, command: String): String {
        val exec = session.openExec(command)
        return try {
            val buf = StringBuilder()
            // Drain stdout AND stderr concurrently. sshj gives each stream its
            // own flow-control window (~2 MB default); if we only read stdout,
            // a remote command that writes enough to stderr can block
            // indefinitely waiting for a read on the other stream, which
            // parks stdout EOF forever. Most callers today redirect with
            // `2>/dev/null`, but the draining is cheap and kills the hazard.
            coroutineScope {
                val stdoutJob = launch {
                    exec.stdout.collect { chunk -> buf.append(String(chunk, Charsets.UTF_8)) }
                }
                val stderrJob = launch { exec.stderr.collect { /* drain, discard */ } }
                stdoutJob.join()
                stderrJob.join()
            }
            exec.exitCode.await()
            buf.toString()
        } finally {
            runCatching { exec.close() }
        }
    }

    private suspend fun execDiscard(session: SshSession, command: String): Int {
        val exec = session.openExec(command)
        return try {
            // Same concurrent-drain rationale as [execCapture].
            coroutineScope {
                val stdoutJob = launch { exec.stdout.collect { /* drain */ } }
                val stderrJob = launch { exec.stderr.collect { /* drain */ } }
                stdoutJob.join()
                stderrJob.join()
            }
            exec.exitCode.await()
        } finally {
            runCatching { exec.close() }
        }
    }

    /**
     * Collect both stdout and stderr line-by-line, calling [onLine] for each
     * completed newline-terminated fragment from either stream. Returns the
     * exit code.
     */
    private suspend fun collectStreamLines(
        exec: ExecChannel,
        onLine: suspend (String) -> Unit,
    ): Int {
        val pending = StringBuilder()
        suspend fun drain(chunk: ByteArray) {
            pending.append(String(chunk, Charsets.UTF_8))
            while (true) {
                val nl = pending.indexOf('\n')
                if (nl < 0) break
                val line = pending.substring(0, nl).trimEnd('\r')
                onLine(line)
                pending.delete(0, nl + 1)
            }
        }

        kotlinx.coroutines.coroutineScope {
            val stdoutJob = this.launch {
                exec.stdout.collect { drain(it) }
            }
            val stderrJob = this.launch {
                exec.stderr.collect { drain(it) }
            }
            stdoutJob.join()
            stderrJob.join()
        }
        if (pending.isNotEmpty()) {
            onLine(pending.toString().trimEnd('\r'))
        }
        return exec.exitCode.await()
    }

    private fun normalizeArch(raw: String): String? = when (raw) {
        "x86_64", "amd64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> null
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    private suspend fun openSession(serverId: UUID, passwordOverride: String?): SshSession {
        val server = serverRepository.getById(serverId)
            ?: throw IllegalStateException("Server not found: $serverId")
        val auth = resolveAuth(server, passwordOverride)
        val target = SshTarget(
            host = server.host,
            port = server.port,
            username = server.username,
            knownHostsPath = knownHostsPath,
        )
        return sshClient.connect(target, auth)
    }

    private suspend fun resolveAuth(server: Server, passwordOverride: String?): SshAuth = when (server.authType) {
        AuthType.PASSWORD -> {
            val pw = passwordOverride
                ?: throw IllegalStateException(
                    "Password required. Passwords aren't persisted yet; re-enter via the wizard.",
                )
            if (pw.isBlank()) {
                throw IllegalStateException("Password is blank")
            }
            SshAuth.Password(pw)
        }
        AuthType.KEY -> {
            val keyId = server.keyPairId
                ?: throw IllegalStateException("Server has no key assigned")
            val keyPair = keyPairRepository.getById(keyId)
                ?: throw IllegalStateException("Linked key not found")
            val privatePem = try {
                secretVault.load(keyPair.keystoreAlias)
            } catch (t: VaultLockedException) {
                throw IllegalStateException("Vault is locked", t)
            } ?: throw IllegalStateException("Private key missing from vault")
            SshAuth.PublicKey(privateKeyPem = privatePem, passphrase = null)
        }
    }
}
