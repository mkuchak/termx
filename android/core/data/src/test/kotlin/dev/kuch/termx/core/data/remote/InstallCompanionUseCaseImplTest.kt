package dev.kuch.termx.core.data.remote

import dev.kuch.termx.core.data.remote.fakes.FakeExecChannel
import dev.kuch.termx.core.data.remote.fakes.FakeKeyPairRepository
import dev.kuch.termx.core.data.remote.fakes.FakeSecretVault
import dev.kuch.termx.core.data.remote.fakes.FakeServerRepository
import dev.kuch.termx.core.data.remote.fakes.FakeSshClient
import dev.kuch.termx.core.data.remote.fakes.FakeSshSession
import dev.kuch.termx.core.data.remote.fakes.FakeTermxReleaseFetcher
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.KeyAlgorithm
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.usecase.InstallCompanionUseCase
import dev.kuch.termx.core.domain.usecase.InstallStep3State
import dev.kuch.termx.libs.sshnative.SshAuth
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InstallCompanionUseCaseImpl].
 *
 * Focuses on the branches where real-user bugs shipped:
 *
 *  - Password auth was rejected at `resolveAuth` until commit `bfa4364`
 *    added the `passwordOverride` plumbing. `PASSWORD + pw set` →
 *    `SshAuth.Password` is the regression guard.
 *  - Vault-locked handling used to surface as a generic crash; we now
 *    emit `InstallStep3State.Error("Vault is locked")`.
 *  - Detect-stage architecture probing has a finite whitelist
 *    (amd64/arm64 only) — unknown archs must not silently fall through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallCompanionUseCaseImplTest {

    private val serverId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val keyId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val knownHosts = "/tmp/known_hosts"

    private fun passwordServer() = Server(
        id = serverId,
        label = "vps",
        host = "example.test",
        port = 22,
        username = "root",
        authType = AuthType.PASSWORD,
        keyPairId = null,
        groupId = null,
        lastConnected = null,
        pingMs = null,
    )

    private fun keyServer(keyPairId: UUID? = keyId) = Server(
        id = serverId,
        label = "vps",
        host = "example.test",
        port = 22,
        username = "root",
        authType = AuthType.KEY,
        keyPairId = keyPairId,
        groupId = null,
        lastConnected = null,
        pingMs = null,
    )

    private fun keyPair(alias: String = "vault-alias") = KeyPair(
        id = keyId,
        label = "dev",
        algorithm = KeyAlgorithm.ED25519,
        publicKey = "ssh-ed25519 AAAAfake",
        keystoreAlias = alias,
        createdAt = Instant.EPOCH,
    )

    private fun makeImpl(
        servers: FakeServerRepository = FakeServerRepository(),
        keys: FakeKeyPairRepository = FakeKeyPairRepository(),
        vault: FakeSecretVault = FakeSecretVault(),
        sshClient: FakeSshClient = FakeSshClient(),
        releaseFetcher: FakeTermxReleaseFetcher = FakeTermxReleaseFetcher(),
    ) = InstallCompanionUseCaseImpl(
        knownHostsPath = knownHosts,
        serverRepository = servers,
        keyPairRepository = keys,
        secretVault = vault,
        sshClient = sshClient,
        releaseFetcher = releaseFetcher,
    )

    // --- openSession / resolveAuth -----------------------------------------

    @Test
    fun `password auth with override connects using SshAuth Password`() = runTest {
        val servers = FakeServerRepository().apply { put(passwordServer()) }
        val sshClient = FakeSshClient()
        val impl = makeImpl(servers = servers, sshClient = sshClient)

        // Detect runs through openSession, then emits states. We don't care
        // about the emitted state here — just that connect was called with
        // the expected auth shape.
        impl.run(
            serverId,
            InstallCompanionUseCase.Stage.Detect,
            InstallCompanionUseCase.Context(passwordOverride = "hunter2"),
        ).toList()

        val captured = sshClient.capturedAuth
        assertTrue(
            "expected SshAuth.Password, got ${captured?.javaClass?.simpleName}",
            captured is SshAuth.Password,
        )
        assertEquals("hunter2", (captured as SshAuth.Password).value)
        assertEquals(knownHosts, sshClient.capturedTarget?.knownHostsPath)
    }

    @Test
    fun `password auth without override emits Error containing Password required`() = runTest {
        val servers = FakeServerRepository().apply { put(passwordServer()) }
        val impl = makeImpl(servers = servers)

        val states = impl.run(
            serverId,
            InstallCompanionUseCase.Stage.Detect,
            InstallCompanionUseCase.Context(passwordOverride = null),
        ).toList()

        val error = states.filterIsInstance<InstallStep3State.Error>().first()
        assertTrue(
            "expected 'Password required' in ${error.message}",
            error.message.contains("Password required"),
        )
    }

    @Test
    fun `password auth with blank override emits Error containing blank`() = runTest {
        val servers = FakeServerRepository().apply { put(passwordServer()) }
        val impl = makeImpl(servers = servers)

        val states = impl.run(
            serverId,
            InstallCompanionUseCase.Stage.Detect,
            InstallCompanionUseCase.Context(passwordOverride = "   "),
        ).toList()

        val error = states.filterIsInstance<InstallStep3State.Error>().first()
        assertTrue(
            "expected 'blank' in ${error.message}",
            error.message.contains("blank"),
        )
    }

    @Test
    fun `key auth with no keyPairId emits Error`() = runTest {
        val servers = FakeServerRepository().apply { put(keyServer(keyPairId = null)) }
        val impl = makeImpl(servers = servers)

        val states = impl.run(
            serverId,
            InstallCompanionUseCase.Stage.Detect,
            InstallCompanionUseCase.Context(),
        ).toList()

        val error = states.filterIsInstance<InstallStep3State.Error>().first()
        assertTrue(
            "expected 'no key' or 'not found' in ${error.message}",
            error.message.lowercase().contains("key"),
        )
    }

    @Test
    fun `key auth with valid key connects using SshAuth PublicKey`() = runTest {
        val servers = FakeServerRepository().apply { put(keyServer()) }
        val keys = FakeKeyPairRepository().apply { put(keyPair()) }
        val vault = FakeSecretVault().apply {
            store("vault-alias", "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray())
        }
        val sshClient = FakeSshClient()
        val impl = makeImpl(servers = servers, keys = keys, vault = vault, sshClient = sshClient)

        impl.run(serverId, InstallCompanionUseCase.Stage.Detect).toList()

        val captured = sshClient.capturedAuth
        assertTrue(
            "expected SshAuth.PublicKey, got ${captured?.javaClass?.simpleName}",
            captured is SshAuth.PublicKey,
        )
    }

    @Test
    fun `key auth with locked vault emits Error containing Vault is locked`() = runTest {
        val servers = FakeServerRepository().apply { put(keyServer()) }
        val keys = FakeKeyPairRepository().apply { put(keyPair()) }
        val vault = FakeSecretVault().apply { locked = true }
        val impl = makeImpl(servers = servers, keys = keys, vault = vault)

        val states = impl.run(serverId, InstallCompanionUseCase.Stage.Detect).toList()

        val error = states.filterIsInstance<InstallStep3State.Error>().first()
        assertTrue(
            "expected 'Vault is locked' in ${error.message}",
            error.message.contains("Vault is locked"),
        )
    }

    // --- Detect stage: termx probe -----------------------------------------

    @Test
    fun `detect with termx on PATH emits AlreadyInstalled with version`() = runTest {
        val servers = FakeServerRepository().apply { put(passwordServer()) }
        val session = FakeSshSession(
            execResponses = mapOf(
                "command -v termx" to FakeExecChannel(stdout = "/home/user/.local/bin/termx\n"),
                "/home/user/.local/bin/termx" to FakeExecChannel(stdout = "termx v0.1.0\n"),
            ),
        )
        val sshClient = FakeSshClient(sessionProvider = { session })
        val impl = makeImpl(servers = servers, sshClient = sshClient)

        val states = impl.run(
            serverId,
            InstallCompanionUseCase.Stage.Detect,
            InstallCompanionUseCase.Context(passwordOverride = "pw"),
        ).toList()

        val ready = states.filterIsInstance<InstallStep3State.AlreadyInstalled>().firstOrNull()
        assertNotNull(
            "expected AlreadyInstalled, got $states",
            ready,
        )
        assertTrue(
            "expected version string in ${ready!!.version}",
            ready.version.contains("termx v0.1.0"),
        )
    }

    @Test
    fun `detect on arm64 with missing termx emits ReadyToDownload`() = runTest {
        val servers = FakeServerRepository().apply { put(passwordServer()) }
        val session = FakeSshSession(
            execResponses = mapOf(
                "command -v termx" to FakeExecChannel(stdout = "\n"),
                "uname -m" to FakeExecChannel(stdout = "aarch64\n"),
            ),
        )
        val sshClient = FakeSshClient(sessionProvider = { session })
        val impl = makeImpl(servers = servers, sshClient = sshClient)

        val states = impl.run(
            serverId,
            InstallCompanionUseCase.Stage.Detect,
            InstallCompanionUseCase.Context(passwordOverride = "pw"),
        ).toList()

        val ready = states.filterIsInstance<InstallStep3State.ReadyToDownload>().firstOrNull()
        assertNotNull("expected ReadyToDownload, got $states", ready)
        assertEquals("arm64", ready!!.arch)
        assertTrue(
            "expected arm64 asset url, got ${ready.downloadUrl}",
            ready.downloadUrl.contains("arm64"),
        )
        assertEquals("termxd-v0.1.0", ready.releaseTag)
    }

    @Test
    fun `detect on unsupported arch emits Error containing the arch string`() = runTest {
        val servers = FakeServerRepository().apply { put(passwordServer()) }
        val session = FakeSshSession(
            execResponses = mapOf(
                "command -v termx" to FakeExecChannel(stdout = "\n"),
                "uname -m" to FakeExecChannel(stdout = "sparc\n"),
            ),
        )
        val sshClient = FakeSshClient(sessionProvider = { session })
        val impl = makeImpl(servers = servers, sshClient = sshClient)

        val states = impl.run(
            serverId,
            InstallCompanionUseCase.Stage.Detect,
            InstallCompanionUseCase.Context(passwordOverride = "pw"),
        ).toList()

        val error = states.filterIsInstance<InstallStep3State.Error>().first()
        assertTrue(
            "expected 'sparc' + 'Unsupported' in ${error.message}",
            error.message.contains("sparc") && error.message.contains("Unsupported"),
        )
    }

    @Test
    fun `detect emits Detecting as the first state`() = runTest {
        val servers = FakeServerRepository().apply { put(passwordServer()) }
        val impl = makeImpl(servers = servers)

        val first = impl.run(
            serverId,
            InstallCompanionUseCase.Stage.Detect,
            InstallCompanionUseCase.Context(passwordOverride = "pw"),
        ).first()

        assertTrue(
            "expected Detecting, got ${first::class.simpleName}",
            first is InstallStep3State.Detecting,
        )
    }
}
