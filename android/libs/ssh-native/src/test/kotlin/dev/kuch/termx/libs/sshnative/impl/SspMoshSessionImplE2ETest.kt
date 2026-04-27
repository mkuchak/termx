package dev.kuch.termx.libs.sshnative.impl

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end mosh test against a REAL `mosh-server` running on
 * loopback. Skipped automatically when `mosh-server` isn't on the
 * machine (CI without the apt package, contributor laptops without
 * mosh installed) so the rest of the suite stays portable.
 *
 * Why this exists: every other test in this module is a unit test.
 * Before v1.1.21 ships we want concrete evidence that the full
 * `SspMoshSessionImpl` ↔ `mosh-server` UDP path actually works on
 * a real network, not just that the pieces compile and that the
 * vendored library passes its own unit tests.
 *
 * The test:
 *  1. Spawns `mosh-server new -s -c 256 -i 127.0.0.1 -p <range>`
 *     with `/bin/cat` as the shell so any byte we send comes back.
 *  2. Parses the canonical `MOSH CONNECT <port> <key>` line out of
 *     mosh-server's stdout — same regex the production handshake
 *     uses.
 *  3. Constructs [SspMoshSessionImpl] with the captured (port, key).
 *  4. Asserts the first server byte arrives within 8 s — proves the
 *     UDP handshake closed and mosh-server pushed an initial state
 *     sync.
 *  5. Writes `"hello-world\n"` and asserts the bytes show up in the
 *     subsequent output stream — proves bidirectional flow works.
 *  6. Closes the session; kills the orphaned mosh-server in @After
 *     in case the close didn't propagate quickly.
 */
class SspMoshSessionImplE2ETest {

    private var moshServerPid: Int = -1
    private var session: SspMoshSessionImpl? = null

    @Before fun skipIfNoMoshServer() {
        assumeTrue(
            "mosh-server not installed; skipping E2E test",
            File("/usr/bin/mosh-server").canExecute() ||
                File("/usr/local/bin/mosh-server").canExecute(),
        )
    }

    @After fun teardown() {
        runCatching { session?.close() }
        if (moshServerPid > 0) {
            // mosh-server's own no-client timeout (60 s) reaps it
            // eventually, but kill explicitly so a flaky test doesn't
            // leave a string of zombie listeners.
            Runtime.getRuntime().exec(arrayOf("kill", "-TERM", moshServerPid.toString()))
                .waitFor()
        }
    }

    @Test fun `e2e roundtrip against real mosh-server`() = runBlocking {
        val (port, key, pid) = startMoshServer()
        moshServerPid = pid

        val s = SspMoshSessionImpl(
            host = "127.0.0.1",
            port = port,
            keyBase64 = key,
            initialCols = 80,
            initialRows = 24,
        )
        session = s

        // 1) First output. The very first emission is our own
        // DECCKM_ON injection (5 bytes). Skip past that and wait for
        // the next chunk, which has to come from the server (proving
        // the UDP handshake landed and a framebuffer snapshot
        // arrived).
        coroutineScope {
            val seenBytes = mutableListOf<ByteArray>()
            val collectorJob = launch(Dispatchers.IO) {
                s.output.collect { seenBytes.add(it) }
            }

            val deadline = System.currentTimeMillis() + 8_000L
            // First emission == DECCKM_ON; the server-driven emission
            // is whatever lands afterwards.
            while (seenBytes.size < 2 && System.currentTimeMillis() < deadline) {
                withContext(Dispatchers.IO) { Thread.sleep(50) }
            }
            assertTrue(
                "expected at least one server-driven emission within 8s; got ${seenBytes.size} chunk(s)",
                seenBytes.size >= 2,
            )
            // Confirm the very first chunk is exactly DECCKM_ON.
            val deccKm = byteArrayOf(0x1B, 0x5B, 0x3F, 0x31, 0x68)
            assertTrue(
                "DECCKM_ON must be the first emission, got ${seenBytes.first().toList()}",
                seenBytes.first().contentEquals(deccKm),
            )

            // 2) Bidirectional. Write "hello-world\n", expect cat to
            // echo it back through mosh-server's framebuffer. The
            // server emits ANSI escape sequences that draw the
            // received bytes; the literal "hello-world" must appear
            // somewhere in the merged output.
            s.write("hello-world\n".toByteArray())
            val echoDeadline = System.currentTimeMillis() + 5_000L
            var sawEcho = false
            while (!sawEcho && System.currentTimeMillis() < echoDeadline) {
                val merged = seenBytes.fold(ByteArray(0)) { acc, b -> acc + b }
                if (merged.toString(Charsets.UTF_8).contains("hello-world")) {
                    sawEcho = true
                }
                if (!sawEcho) withContext(Dispatchers.IO) { Thread.sleep(100) }
            }
            collectorJob.cancel()
            assertTrue(
                "expected 'hello-world' to echo back through mosh-server within 5s",
                sawEcho,
            )
        }

        // 3) Clean shutdown. close() is idempotent; nothing should
        // throw, and the test's @After will mop up the server.
        s.close()
    }

    private fun startMoshServer(): Triple<Int, String, Int> {
        val proc = ProcessBuilder(
            "mosh-server", "new",
            "-s",
            "-c", "256",
            "-i", "127.0.0.1",
            "-p", "60001:60020",
            "--", "/bin/cat",
        ).redirectErrorStream(true).start()

        // mosh-server prints CONNECT line + version banner + the
        // "[mosh-server detached, pid = N]" line, then the PARENT
        // exits (the child detached daemon stays running on UDP).
        // We just slurp the full parent output and regex once at the
        // end — partial-byte regex scanning gave us a `\S+` greedy
        // match on the truncated key, which then fed garbage into
        // the base64 decoder.
        val finished = proc.waitFor(5L, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            error("mosh-server parent didn't detach within 5s")
        }
        val output = proc.inputStream.bufferedReader().use { it.readText() }

        val connect = Regex("""MOSH CONNECT (\d+) (\S+)""").find(output)
        val detached = Regex("""mosh-server detached, pid\s*=\s*(\d+)""").find(output)

        assertNotNull("Failed to parse MOSH CONNECT line:\n$output", connect)
        assertNotNull("Failed to parse detached pid:\n$output", detached)

        val port = connect!!.groupValues[1].toInt()
        val key = connect.groupValues[2]
        val pid = detached!!.groupValues[1].toInt()
        return Triple(port, key, pid)
    }
}

