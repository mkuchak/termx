package dev.kuch.termx.libs.companion.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NdjsonBufferTest {

    @Test
    fun `splits on single newline and retains tail`() {
        val buf = NdjsonBuffer()
        val out = buf.append("abc\ndef")
        assertEquals(listOf("abc"), out)
        assertEquals("def", buf.flushRemaining())
    }

    @Test
    fun `subsequent chunk completes previous tail and yields both lines`() {
        val buf = NdjsonBuffer()
        buf.append("abc\ndef")
        val out = buf.append("\n123\n")
        assertEquals(listOf("def", "123"), out)
        assertNull(buf.flushRemaining())
    }

    @Test
    fun `joins partial chunks before newline`() {
        val buf = NdjsonBuffer()
        val first = buf.append("partial")
        assertEquals(emptyList<String>(), first)
        val second = buf.append("rest\n")
        assertEquals(listOf("partialrest"), second)
        assertNull(buf.flushRemaining())
    }

    @Test
    fun `handles CRLF line endings`() {
        val buf = NdjsonBuffer()
        val out = buf.append("hello\r\nworld\r\n")
        assertEquals(listOf("hello", "world"), out)
        assertNull(buf.flushRemaining())
    }

    @Test
    fun `flushRemaining returns null when empty`() {
        val buf = NdjsonBuffer()
        assertNull(buf.flushRemaining())
        buf.append("line\n")
        assertNull(buf.flushRemaining())
    }

    @Test
    fun `flushRemaining clears buffer`() {
        val buf = NdjsonBuffer()
        buf.append("leftover")
        assertEquals("leftover", buf.flushRemaining())
        assertNull(buf.flushRemaining())
    }

    @Test
    fun `empty chunk is a no-op`() {
        val buf = NdjsonBuffer()
        assertEquals(emptyList<String>(), buf.append(""))
        assertNull(buf.flushRemaining())
    }

    @Test
    fun `consecutive newlines emit empty lines`() {
        val buf = NdjsonBuffer()
        val out = buf.append("a\n\nb\n")
        assertEquals(listOf("a", "", "b"), out)
    }
}
