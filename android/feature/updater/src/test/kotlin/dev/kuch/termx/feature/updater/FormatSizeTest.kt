package dev.kuch.termx.feature.updater

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Banner copy includes the download size; users see this and decide
 * whether to spend their data plan. Formatting must round into the
 * right unit and stay short.
 */
class FormatSizeTest {

    @Test fun `zero or negative renders as question mark`() {
        assertEquals("?", formatSize(0L))
        assertEquals("?", formatSize(-1L))
    }

    @Test fun `bytes under 1 KiB render as bytes`() {
        assertEquals("512 B", formatSize(512L))
    }

    @Test fun `KiB-range values render with one decimal`() {
        assertEquals("1.0 KB", formatSize(1024L))
        assertEquals("1.5 KB", formatSize(1536L))
    }

    @Test fun `MiB-range values render with one decimal`() {
        assertEquals("12.0 MB", formatSize(12L * 1024L * 1024L))
        // 12.5 MiB
        assertEquals("12.5 MB", formatSize((12.5 * 1024 * 1024).toLong()))
    }

    @Test fun `GiB-range values render with two decimals`() {
        assertEquals("1.50 GB", formatSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }
}
