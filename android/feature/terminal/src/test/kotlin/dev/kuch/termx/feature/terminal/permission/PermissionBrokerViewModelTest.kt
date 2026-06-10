package dev.kuch.termx.feature.terminal.permission

import dev.kuch.termx.core.data.session.EventStreamHub
import dev.kuch.termx.feature.terminal.MainDispatcherRule
import dev.kuch.termx.feature.terminal.fakes.FakeSshSession
import dev.kuch.termx.libs.companion.EventStreamClient
import dev.kuch.termx.libs.companion.events.ApprovalResponse
import dev.kuch.termx.libs.companion.events.EventParser
import dev.kuch.termx.libs.companion.events.TermxEvent
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pins the broker's phone→VPS return path: Approve/Deny/Always must call
 * [EventStreamClient.respondToApproval] (the direct SFTP write of
 * `~/.termx/approvals/<id>.res.json` that termxd's PreToolUse hook polls)
 * — NOT the legacy `sendCommand` path, which nothing on the VPS consumes.
 *
 * Fakes follow the repo convention: hand-rolled open-class subclass
 * ([FakeEventStreamClient]), no mock library.
 */
class PermissionBrokerViewModelTest {

    // Real Unconfined so viewModelScope work runs eagerly on the test
    // thread; async edges are absorbed with waitUntil() polling, matching
    // the TerminalViewModel test convention.
    @get:Rule
    val mainRule = MainDispatcherRule(Dispatchers.Unconfined)

    private val serverId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val parser = EventParser()

    /** Hand-rolled subclass fake — records decisions instead of SFTPing them. */
    private class FakeEventStreamClient : EventStreamClient(FakeSshSession()) {
        data class RecordedResponse(
            val requestId: String,
            val decision: ApprovalResponse.Decision,
            val reason: String?,
        )

        val events = MutableSharedFlow<TermxEvent>(replay = 16)
        val responses = CopyOnWriteArrayList<RecordedResponse>()
        val allowlistAppends = CopyOnWriteArrayList<String>()
        var failRespond = false
        var failAllowlistAppend = false

        override fun stream(): Flow<TermxEvent> = events

        override suspend fun respondToApproval(
            requestId: String,
            decision: ApprovalResponse.Decision,
            reason: String?,
        ) {
            if (failRespond) error("simulated SFTP failure")
            responses += RecordedResponse(requestId, decision, reason)
        }

        override suspend fun appendAllowlistRule(pattern: String) {
            if (failAllowlistAppend) error("simulated allowlist failure")
            allowlistAppends += pattern
        }
    }

    /**
     * Build events through the real wire parser rather than constructors so
     * the test exercises the same decode path production uses.
     */
    private fun permissionRequested(requestId: String, toolName: String = "Bash"): TermxEvent =
        parser.decodeLine(
            """{"type":"permission_requested","ts":"2026-06-10T12:00:00Z","session":"main",""" +
                """"request_id":"$requestId","tool_name":"$toolName",""" +
                """"tool_args":{"command":"rm -rf /tmp/x"}}""",
        )!!

    private fun permissionResolved(requestId: String): TermxEvent =
        parser.decodeLine(
            """{"type":"permission_resolved","ts":"2026-06-10T12:00:01Z","session":"main",""" +
                """"request_id":"$requestId","decision":"allow","reason":""}""",
        )!!

    private fun newBroker(): Triple<PermissionBrokerViewModel, EventStreamHub, FakeEventStreamClient> {
        val hub = EventStreamHub()
        val vm = PermissionBrokerViewModel(hub)
        val client = FakeEventStreamClient()
        hub.publish(serverId, "prod-1", client)
        return Triple(vm, hub, client)
    }

    /** Busy-wait on a predicate up to ~5s. */
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    @Test
    fun permissionRequested_addsPendingEntry_andResolvedRemovesIt() {
        val (vm, _, client) = newBroker()

        client.events.tryEmit(permissionRequested("req-1"))
        waitUntil { vm.pendingRequests.value.isNotEmpty() }

        val pending = vm.pendingRequests.value.single()
        assertEquals("req-1", pending.approvalId)
        assertEquals("Bash", pending.toolName)
        assertEquals(serverId, pending.serverId)
        assertEquals("prod-1", pending.serverLabel)

        // The hook emits permission_resolved after reading the response —
        // the existing dedup/removal on that event must stay intact.
        client.events.tryEmit(permissionResolved("req-1"))
        waitUntil { vm.pendingRequests.value.isEmpty() }
        assertTrue(vm.pendingRequests.value.isEmpty())
    }

    @Test
    fun duplicatePermissionRequested_isDeduped() {
        val (vm, _, client) = newBroker()

        client.events.tryEmit(permissionRequested("req-1"))
        client.events.tryEmit(permissionRequested("req-1"))
        waitUntil { vm.pendingRequests.value.isNotEmpty() }

        assertEquals(1, vm.pendingRequests.value.size)
    }

    @Test
    fun approve_respondsAllow_andOptimisticallyRemovesEntry() {
        val (vm, _, client) = newBroker()
        client.events.tryEmit(permissionRequested("req-1"))
        waitUntil { vm.pendingRequests.value.isNotEmpty() }

        vm.approve("req-1")

        // Optimistic local removal — must not wait on the SFTP hop.
        assertTrue(vm.pendingRequests.value.isEmpty())
        waitUntil { client.responses.isNotEmpty() }
        val response = client.responses.single()
        assertEquals("req-1", response.requestId)
        assertEquals(ApprovalResponse.Decision.ALLOW, response.decision)
        assertEquals(null, response.reason)
        // Plain approve must NOT touch the allowlist.
        assertTrue(client.allowlistAppends.isEmpty())
    }

    @Test
    fun deny_respondsDeny_withReasonPassedThrough() {
        val (vm, _, client) = newBroker()
        client.events.tryEmit(permissionRequested("req-2"))
        waitUntil { vm.pendingRequests.value.isNotEmpty() }

        vm.deny("req-2", reason = "Nope")

        assertTrue(vm.pendingRequests.value.isEmpty())
        waitUntil { client.responses.isNotEmpty() }
        val response = client.responses.single()
        assertEquals("req-2", response.requestId)
        assertEquals(ApprovalResponse.Decision.DENY, response.decision)
        assertEquals("Nope", response.reason)
    }

    @Test
    fun alwaysApprove_respondsAllow_andAppendsEscapedAllowlistRule() {
        val (vm, _, client) = newBroker()
        client.events.tryEmit(permissionRequested("req-3", toolName = "Bash"))
        waitUntil { vm.pendingRequests.value.isNotEmpty() }

        vm.alwaysApprove("req-3")

        assertTrue(vm.pendingRequests.value.isEmpty())
        waitUntil { client.responses.isNotEmpty() && client.allowlistAppends.isNotEmpty() }
        // "always" is ALLOW on the wire — hook_pretooluse.go treats any
        // other decision string (including "always") as a deny.
        val response = client.responses.single()
        assertEquals("req-3", response.requestId)
        assertEquals(ApprovalResponse.Decision.ALLOW, response.decision)
        // Rule shape: ^<Regex.escape(toolName)>\|.*$ over the hook's
        // `<tool_name>|<cmd>` candidate.
        assertEquals(listOf("""^\QBash\E\|.*$"""), client.allowlistAppends.toList())
    }

    @Test
    fun alwaysApprove_allowlistFailureIsSwallowed_decisionStillWritten() {
        val (vm, _, client) = newBroker()
        client.failAllowlistAppend = true
        client.events.tryEmit(permissionRequested("req-4"))
        waitUntil { vm.pendingRequests.value.isNotEmpty() }

        vm.alwaysApprove("req-4")

        assertTrue(vm.pendingRequests.value.isEmpty())
        // The decision write is the critical half and must land even when
        // the best-effort allowlist append blows up.
        waitUntil { client.responses.isNotEmpty() }
        assertEquals(ApprovalResponse.Decision.ALLOW, client.responses.single().decision)
        assertTrue(client.allowlistAppends.isEmpty())
    }

    @Test
    fun alwaysApprove_decisionFailureStillAttemptsAllowlist_andStaysOptimistic() {
        val (vm, _, client) = newBroker()
        client.failRespond = true
        client.events.tryEmit(permissionRequested("req-5", toolName = "Edit"))
        waitUntil { vm.pendingRequests.value.isNotEmpty() }

        vm.alwaysApprove("req-5")

        // Optimistic removal holds; the hook's 30s timeout-deny resolves
        // the request server-side and the PermissionResolved event no-ops.
        assertTrue(vm.pendingRequests.value.isEmpty())
        waitUntil { client.allowlistAppends.isNotEmpty() }
        assertEquals(listOf("""^\QEdit\E\|.*$"""), client.allowlistAppends.toList())
    }

    @Test
    fun approve_forUnknownRequestId_isNoOp() {
        val (vm, _, client) = newBroker()

        vm.approve("ghost")

        assertTrue(vm.pendingRequests.value.isEmpty())
        assertTrue(client.responses.isEmpty())
    }
}
