package dev.kuch.termx.feature.servers

import dev.kuch.termx.core.data.prefs.PasswordCache
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.KeyAlgorithm
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.feature.servers.fakes.FakeKeyPairRepository
import dev.kuch.termx.feature.servers.fakes.FakeSecretVault
import dev.kuch.termx.feature.servers.fakes.FakeServerGroupRepository
import dev.kuch.termx.feature.servers.fakes.FakeServerRepository
import dev.kuch.termx.feature.servers.fakes.FakeSshClient
import dev.kuch.termx.libs.sshnative.SshException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [AddEditServerViewModel].
 *
 * Covers three invariants:
 *
 *  - `save()` never writes the raw password into the persisted
 *    [dev.kuch.termx.core.domain.model.Server] row's plaintext string
 *    fields. The bytes live in [dev.kuch.termx.core.data.vault.SecretVault]
 *    addressed by `passwordAlias` — leaking the plaintext into
 *    `label`/`host`/etc. would be a spec regression.
 *  - `save()` with password auth actually writes the password bytes to
 *    the vault under alias `password-${id}` and seeds the in-memory
 *    cache.
 *  - Every [SshException] subclass maps to a user-visible, non-generic
 *    TestResult.Error message. The strings are what users read after a
 *    failed Test connection — they must not surface stack traces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddEditServerViewModelTest {

    // Real Unconfined avoids the virtual-clock trap inside
    // `withTimeoutOrNull(...) { withContext(Dispatchers.IO) { ... } }`:
    // under a TestDispatcher the 10s timeout fires before the real IO
    // thread returns, so every mapping test would see "Timed out" back.
    @get:Rule
    val mainRule = MainDispatcherRule(Dispatchers.Unconfined)

    private val knownHosts = "/tmp/known_hosts"

    private fun vm(
        servers: FakeServerRepository = FakeServerRepository(),
        keys: FakeKeyPairRepository = FakeKeyPairRepository(),
        vault: FakeSecretVault = FakeSecretVault(),
        passwordCache: PasswordCache = PasswordCache(),
        sshClient: FakeSshClient = FakeSshClient(),
    ): AddEditServerViewModel = AddEditServerViewModel(
        knownHostsPath = knownHosts,
        serverRepository = servers,
        keyPairRepository = keys,
        serverGroupRepository = FakeServerGroupRepository(),
        secretVault = vault,
        passwordCache = passwordCache,
        sshClient = sshClient,
    )

    // --- save() -----------------------------------------------------------

    @Test
    fun `save with password auth stores password in vault and tags row with alias`() = runTest {
        val servers = FakeServerRepository()
        val vault = FakeSecretVault()
        val cache = PasswordCache()
        val vm = vm(servers = servers, vault = vault, passwordCache = cache)
        vm.initialize(null)
        vm.onHostChange("example.test")
        vm.onUsernameChange("root")
        vm.onAuthTypeChange(AuthType.PASSWORD)
        vm.onPasswordChange("super-secret")

        val id = vm.save()

        val persisted = servers.upserts.last()
        assertEquals(id, persisted.id)
        assertEquals(AuthType.PASSWORD, persisted.authType)
        assertNull("password row must never carry a keyPairId", persisted.keyPairId)
        assertEquals("password-$id", persisted.passwordAlias)

        // Raw plaintext must never appear in any persisted string field.
        val passwordLeakCandidates = listOf(
            persisted.label,
            persisted.host,
            persisted.username,
            persisted.tmuxSessionName,
        )
        assertFalse(
            "password must not appear in any persisted string field",
            passwordLeakCandidates.any { it.contains("super-secret") },
        )

        // Vault round-trip: the bytes are UTF-8 of the password.
        val fromVault = vault.load("password-$id")
        assertNotNull(fromVault)
        assertEquals("super-secret", String(fromVault!!, Charsets.UTF_8))

        // In-memory cache is seeded so any live terminal VM can reuse it.
        assertEquals("super-secret", cache.get(id))
    }

    @Test
    fun `save with key auth persists the selected keyPairId and no passwordAlias`() = runTest {
        val keyId = UUID.randomUUID()
        val keys = FakeKeyPairRepository().apply {
            put(
                KeyPair(
                    id = keyId,
                    label = "dev",
                    algorithm = KeyAlgorithm.ED25519,
                    publicKey = "ssh-ed25519 AAAAfake",
                    keystoreAlias = "alias",
                    createdAt = Instant.EPOCH,
                ),
            )
        }
        val servers = FakeServerRepository()
        val vm = vm(servers = servers, keys = keys)
        vm.initialize(null)
        vm.onHostChange("example.test")
        vm.onUsernameChange("root")
        vm.onAuthTypeChange(AuthType.KEY)
        vm.onKeyPairSelected(keyId)

        vm.save()

        val persisted = servers.upserts.last()
        assertEquals(keyId, persisted.keyPairId)
        assertEquals(AuthType.KEY, persisted.authType)
        assertNull("key-auth row must not carry a passwordAlias", persisted.passwordAlias)
    }

    // --- testConnection() error mapping ----------------------------------

    private suspend fun mapException(t: Throwable): String {
        val sshClient = FakeSshClient(connectException = t)
        val v = vm(sshClient = sshClient)
        v.initialize(null)
        v.onHostChange("example.test")
        v.onUsernameChange("root")
        v.onAuthTypeChange(AuthType.PASSWORD)
        v.onPasswordChange("pw")
        v.testConnection()

        // runTestConnection hops to Dispatchers.IO; wait on the state
        // rather than the scheduler so we don't fight that real-thread
        // boundary. The flow publishes TestResult.Running first, then
        // the Error — filter for the terminal state.
        val out = v.state.first {
            it.testResult is TestResult.Error || it.testResult is TestResult.Success
        }.testResult
        assertTrue(
            "expected TestResult.Error, got ${out::class.simpleName}",
            out is TestResult.Error,
        )
        return (out as TestResult.Error).message
    }

    // These tests use `runBlocking` instead of `runTest` because
    // `runTestConnection` wraps the connect call in `withTimeoutOrNull` +
    // `withContext(Dispatchers.IO)`. Under `runTest`'s virtual clock the
    // 10s timeout fires before the real IO thread returns — every
    // assertion would see "Timed out after 10s." regardless of the
    // underlying exception. `runBlocking` uses real time, so the fake's
    // synchronous throw wins the race.

    @Test
    fun `AuthFailed exception maps to 'Authentication failed'`() = runBlocking {
        val msg = mapException(SshException.AuthFailed())
        assertTrue("got '$msg'", msg.contains("Authentication failed"))
    }

    @Test
    fun `HostUnreachable maps to 'not reachable'`() = runBlocking {
        val msg = mapException(SshException.HostUnreachable())
        assertTrue("got '$msg'", msg.contains("not reachable"))
    }

    @Test
    fun `HostKeyMismatch maps to 'Host key changed'`() = runBlocking {
        val msg = mapException(SshException.HostKeyMismatch())
        assertTrue("got '$msg'", msg.contains("Host key changed"))
    }

    @Test
    fun `TimedOut maps to 'Timed out'`() = runBlocking {
        val msg = mapException(SshException.TimedOut())
        assertTrue("got '$msg'", msg.contains("Timed out"))
    }

    @Test
    fun `ChannelClosed maps to 'closed'`() = runBlocking {
        val msg = mapException(SshException.ChannelClosed())
        assertTrue("got '$msg'", msg.contains("closed"))
    }

    @Test
    fun `Unknown exception surfaces the message`() = runBlocking {
        val msg = mapException(SshException.Unknown("transport hiccup"))
        assertTrue("got '$msg'", msg.contains("transport hiccup"))
    }
}
