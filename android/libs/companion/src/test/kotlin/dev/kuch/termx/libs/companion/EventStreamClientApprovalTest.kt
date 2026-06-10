package dev.kuch.termx.libs.companion

import dev.kuch.termx.libs.companion.events.ApprovalResponse
import dev.kuch.termx.libs.companion.fakes.FakeExecChannel
import dev.kuch.termx.libs.companion.fakes.FakeSftpClient
import dev.kuch.termx.libs.companion.fakes.FakeSshSession
import dev.kuch.termx.libs.companion.fakes.bytesFlowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-contract lock for [EventStreamClient.respondToApproval] and
 * [EventStreamClient.appendAllowlistRule] — the phone-side half of the
 * permission broker.
 *
 * The `.res.json` payloads are asserted BYTE-FOR-BYTE against the golden
 * fixtures in `src/test/resources/approvals-golden/` (mirroring the
 * `events-golden.ndjson` approach). The reader is the Go struct
 * `approvalResponse` in `termxd/cmd/hook_pretooluse.go`:
 *
 * ```go
 * Decision string `json:"decision"` // only "approve"/"allow" unblock
 * Reason   string `json:"reason,omitempty"`
 * ```
 *
 * A field rename on either side must fail here before it ships. Note
 * there is no "always" wire value: the hook treats anything that isn't
 * approve/allow as deny, so "Always approve" is `allow` on the wire
 * (fixture `always.res.json`, identical to `allow.res.json`) plus a
 * phone-side allowlist append.
 */
class EventStreamClientApprovalTest {

    private fun golden(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("approvals-golden/$name")
        assertNotNull("approvals-golden/$name must be on the test classpath", stream)
        return stream!!.readBytes()
    }

    private fun newClient(home: String = "/home/test"): Pair<EventStreamClient, FakeSftpClient> {
        val session = FakeSshSession()
        session.queueExec("printf %s \"\$HOME\"") {
            FakeExecChannel(stdout = bytesFlowOf(home))
        }
        val sftp = FakeSftpClient()
        session.setSftpFactory { sftp }
        return EventStreamClient(session) to sftp
    }

    // ── golden byte locks ────────────────────────────────────────────────

    @Test
    fun `approve writes the exact allow golden bytes to the res json path`() = runTest {
        val (client, sftp) = newClient()

        client.respondToApproval("req-1", ApprovalResponse.Decision.ALLOW)

        val bytes = sftp.writes["/home/test/.termx/approvals/req-1.res.json"]
        assertNotNull("expected a write at the .res.json path", bytes)
        assertArrayEquals(golden("allow.res.json"), bytes)
    }

    @Test
    fun `deny without reason writes the exact deny golden bytes (reason omitted like Go omitempty)`() =
        runTest {
            val (client, sftp) = newClient()

            client.respondToApproval("req-2", ApprovalResponse.Decision.DENY)

            val bytes = sftp.writes["/home/test/.termx/approvals/req-2.res.json"]
            assertNotNull(bytes)
            assertArrayEquals(golden("deny.res.json"), bytes)
        }

    @Test
    fun `deny with reason writes the exact deny-with-reason golden bytes`() = runTest {
        val (client, sftp) = newClient()

        client.respondToApproval(
            requestId = "req-3",
            decision = ApprovalResponse.Decision.DENY,
            reason = "Denied from notification",
        )

        val bytes = sftp.writes["/home/test/.termx/approvals/req-3.res.json"]
        assertNotNull(bytes)
        assertArrayEquals(golden("deny-with-reason.res.json"), bytes)
    }

    @Test
    fun `always approve is allow on the wire - golden fixtures are byte-identical`() = runTest {
        // The Go hook (hook_pretooluse.go) accepts only "approve"/"allow";
        // an "always" string would be a DENY. The persistence half of
        // "always" lives in appendAllowlistRule, not in this payload.
        assertArrayEquals(golden("allow.res.json"), golden("always.res.json"))

        val (client, sftp) = newClient()
        client.respondToApproval("req-4", ApprovalResponse.Decision.ALLOW)
        assertArrayEquals(
            golden("always.res.json"),
            sftp.writes["/home/test/.termx/approvals/req-4.res.json"],
        )
    }

    // ── atomicity ────────────────────────────────────────────────────────

    @Test
    fun `respondToApproval publishes via tmp-then-rename so the polling hook never sees a torn file`() =
        runTest {
            val (client, sftp) = newClient(home = "/root")

            client.respondToApproval("req-5", ApprovalResponse.Decision.ALLOW)

            val finalPath = "/root/.termx/approvals/req-5.res.json"
            val rename = synchronized(sftp.renames) { sftp.renames.toList() }.single()
            assertTrue(
                "expected unique tmp sibling as rename src: ${rename.first}",
                rename.first.startsWith("$finalPath$TEMP_SUFFIX_SEPARATOR"),
            )
            assertEquals(finalPath, rename.second)
            assertNotNull(sftp.writes[finalPath])
            assertTrue(sftp.writes.none { it.key.startsWith("$finalPath$TEMP_SUFFIX_SEPARATOR") })
        }

    // ── allowlist read-modify-write ──────────────────────────────────────

    @Test
    fun `appendAllowlistRule creates the file when missing`() = runTest {
        val (client, sftp) = newClient()
        // No canned read at the allowlist path → FakeSftpClient.read throws,
        // which is exactly what a real SFTP does for a missing file.

        client.appendAllowlistRule("""^\QBash\E\|.*$""")

        val bytes = sftp.writes["/home/test/.termx/allowlist.txt"]
        assertNotNull(bytes)
        assertEquals("^\\QBash\\E\\|.*$\n", bytes!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `appendAllowlistRule appends to existing content and repairs a missing trailing newline`() =
        runTest {
            val (client, sftp) = newClient()
            sftp.reads["/home/test/.termx/allowlist.txt"] =
                "# hand-written rules\n^Bash\\|npm test$".toByteArray(Charsets.UTF_8)

            client.appendAllowlistRule("""^\QEdit\E\|.*$""")

            val written = sftp.writes["/home/test/.termx/allowlist.txt"]!!
                .toString(Charsets.UTF_8)
            assertEquals(
                "# hand-written rules\n^Bash\\|npm test$\n^\\QEdit\\E\\|.*$\n",
                written,
            )
        }

    @Test
    fun `appendAllowlistRule is idempotent - present pattern causes no write`() = runTest {
        val (client, sftp) = newClient()
        sftp.reads["/home/test/.termx/allowlist.txt"] =
            "^\\QBash\\E\\|.*$\n".toByteArray(Charsets.UTF_8)

        client.appendAllowlistRule("""^\QBash\E\|.*$""")

        assertTrue("no SFTP write expected for a duplicate rule", sftp.writes.isEmpty())
        assertTrue(
            "no SFTP rename expected for a duplicate rule",
            synchronized(sftp.renames) { sftp.renames.isEmpty() },
        )
    }

    @Test
    fun `appendAllowlistRule rewrites atomically`() = runTest {
        val (client, sftp) = newClient()

        client.appendAllowlistRule("""^\QWrite\E\|.*$""")

        val finalPath = "/home/test/.termx/allowlist.txt"
        val rename = synchronized(sftp.renames) { sftp.renames.toList() }.single()
        assertTrue(rename.first.startsWith("$finalPath$TEMP_SUFFIX_SEPARATOR"))
        assertEquals(finalPath, rename.second)
    }
}
