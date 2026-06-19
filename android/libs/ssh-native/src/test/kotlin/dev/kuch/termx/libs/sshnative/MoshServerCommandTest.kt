package dev.kuch.termx.libs.sshnative

import dev.kuch.termx.libs.sshnative.impl.MoshClientImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure mosh-server bootstrap line assembly. The line
 * starts with the `LANG=C.UTF-8 LC_ALL=C.UTF-8` env-assignment prefix
 * (non-login exec shells on minimal VPSes land in C/POSIX and mosh-server
 * hard-refuses without a UTF-8 locale), binds via `-s` ALONE (it follows
 * the `$SSH_CONNECTION` address family — no `-i`, which used to pin the
 * IPv4 wildcard and broke IPv6 servers), and appends the optional startup
 * command verbatim after `-- ` (it arrives pre-wrapped from the caller),
 * only when it is non-blank.
 */
class MoshServerCommandTest {

    @Test
    fun `no startup command yields the bare mosh-server line with locale prefix`() {
        val cmd = MoshClientImpl.moshServerCommand(
            portRange = "60000:60010",
            startupCommand = null,
        )
        assertEquals(
            "LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -p 60000:60010",
            cmd,
        )
    }

    @Test
    fun `startup command is appended verbatim after a double dash`() {
        val cmd = MoshClientImpl.moshServerCommand(
            portRange = "60000:60010",
            startupCommand = "X",
        )
        assertEquals(
            "LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -p 60000:60010 -- X",
            cmd,
        )
    }

    @Test
    fun `blank startup command is treated as no command`() {
        val cmd = MoshClientImpl.moshServerCommand(
            portRange = "60000:60010",
            startupCommand = "   ",
        )
        assertEquals(
            "LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -p 60000:60010",
            cmd,
        )
    }

    @Test
    fun `pre-wrapped command is not re-quoted or escaped`() {
        val wrapped = "\${SHELL:-/bin/sh} -lc 'tmux attach || tmux new'"
        val cmd = MoshClientImpl.moshServerCommand(
            portRange = "60000:60010",
            startupCommand = wrapped,
        )
        assertEquals(
            "LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -p 60000:60010 -- $wrapped",
            cmd,
        )
    }

    @Test
    fun `locale prefix precedes the binary and never leaks past the double dash`() {
        val cmd = MoshClientImpl.moshServerCommand(
            portRange = "60000:60010",
            startupCommand = "htop",
        )
        // POSIX env-assignment semantics only apply BEFORE the command word;
        // after ` -- ` every token belongs verbatim to the startup command.
        assertTrue(cmd.startsWith("LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server "))
        assertEquals("htop", cmd.substringAfter(" -- "))
    }

    @Test
    fun `binds via -s and never -i so the SSH address family governs (IPv4 and IPv6)`() {
        // Regression guard for the IPv4-only-bind bug: `-i 0.0.0.0` is
        // getopt last-wins and overrode `-s`, pinning the IPv4 wildcard so
        // any IPv6-reached server bound a socket the client could never
        // reach. The fix drops `-i` entirely and lets `-s` follow
        // $SSH_CONNECTION. Assert on the SERVER (`-s`) portion only — the
        // verbatim startup command after ` -- ` is the user's and may
        // legitimately contain "-i".
        val server = MoshClientImpl.moshServerCommand(
            portRange = "60000:60010",
            startupCommand = "vim -i NONE",
        ).substringBefore(" -- ")
        assertTrue("must bind with -s", server.contains(" -s "))
        assertFalse("must NOT pin a bind IP with -i", server.contains(" -i "))
    }
}
