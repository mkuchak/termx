package dev.kuch.termx.libs.sshnative

import dev.kuch.termx.libs.sshnative.impl.MoshClientImpl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure mosh-server bootstrap line assembly. The
 * optional startup command must be appended verbatim after `-- ` (it
 * arrives pre-wrapped from the caller), and only when it is non-blank.
 */
class MoshServerCommandTest {

    @Test
    fun `no startup command yields the bare mosh-server line`() {
        val cmd = MoshClientImpl.moshServerCommand(
            bindIp = "1.2.3.4",
            portRange = "60000:60010",
            startupCommand = null,
        )
        assertEquals("mosh-server new -s -c 256 -i 1.2.3.4 -p 60000:60010", cmd)
    }

    @Test
    fun `startup command is appended verbatim after a double dash`() {
        val cmd = MoshClientImpl.moshServerCommand(
            bindIp = "1.2.3.4",
            portRange = "60000:60010",
            startupCommand = "X",
        )
        assertEquals("mosh-server new -s -c 256 -i 1.2.3.4 -p 60000:60010 -- X", cmd)
    }

    @Test
    fun `blank startup command is treated as no command`() {
        val cmd = MoshClientImpl.moshServerCommand(
            bindIp = "1.2.3.4",
            portRange = "60000:60010",
            startupCommand = "   ",
        )
        assertEquals("mosh-server new -s -c 256 -i 1.2.3.4 -p 60000:60010", cmd)
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
            "mosh-server new -s -c 256 -i 0.0.0.0 -p 60000:60010 -- $wrapped",
            cmd,
        )
    }
}
