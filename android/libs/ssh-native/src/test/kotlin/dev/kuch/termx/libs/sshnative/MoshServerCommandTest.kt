package dev.kuch.termx.libs.sshnative

import dev.kuch.termx.libs.sshnative.impl.MoshClientImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure mosh-server bootstrap line assembly. The line
 * starts with the `LANG=C.UTF-8 LC_ALL=C.UTF-8` env-assignment prefix
 * (non-login exec shells on minimal VPSes land in C/POSIX and mosh-server
 * hard-refuses without a UTF-8 locale), and the optional startup command
 * must be appended verbatim after `-- ` (it arrives pre-wrapped from the
 * caller), and only when it is non-blank.
 */
class MoshServerCommandTest {

    @Test
    fun `no startup command yields the bare mosh-server line with locale prefix`() {
        val cmd = MoshClientImpl.moshServerCommand(
            bindIp = "1.2.3.4",
            portRange = "60000:60010",
            startupCommand = null,
        )
        assertEquals(
            "LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -i 1.2.3.4 -p 60000:60010",
            cmd,
        )
    }

    @Test
    fun `startup command is appended verbatim after a double dash`() {
        val cmd = MoshClientImpl.moshServerCommand(
            bindIp = "1.2.3.4",
            portRange = "60000:60010",
            startupCommand = "X",
        )
        assertEquals(
            "LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -i 1.2.3.4 -p 60000:60010 -- X",
            cmd,
        )
    }

    @Test
    fun `blank startup command is treated as no command`() {
        val cmd = MoshClientImpl.moshServerCommand(
            bindIp = "1.2.3.4",
            portRange = "60000:60010",
            startupCommand = "   ",
        )
        assertEquals(
            "LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -i 1.2.3.4 -p 60000:60010",
            cmd,
        )
    }

    @Test
    fun `pre-wrapped command is not re-quoted or escaped`() {
        val wrapped = "\${SHELL:-/bin/sh} -lc 'tmux attach || tmux new'"
        val cmd = MoshClientImpl.moshServerCommand(
            bindIp = "0.0.0.0",
            portRange = "60000:60010",
            startupCommand = wrapped,
        )
        assertEquals(
            "LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -i 0.0.0.0 -p 60000:60010 -- $wrapped",
            cmd,
        )
    }

    @Test
    fun `locale prefix precedes the binary and never leaks past the double dash`() {
        val cmd = MoshClientImpl.moshServerCommand(
            bindIp = "0.0.0.0",
            portRange = "60000:60010",
            startupCommand = "htop",
        )
        // POSIX env-assignment semantics only apply BEFORE the command word;
        // after ` -- ` every token belongs verbatim to the startup command.
        assertTrue(cmd.startsWith("LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server "))
        assertEquals("htop", cmd.substringAfter(" -- "))
    }
}
