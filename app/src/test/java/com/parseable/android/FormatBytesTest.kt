package com.parseable.android

import com.parseable.android.data.formatBytes
import org.junit.Assert.*
import org.junit.Test

class FormatBytesTest {

    @Test
    fun `null input returns null`() {
        assertNull(formatBytes(null))
    }

    @Test
    fun `zero bytes`() {
        assertEquals("0 B", formatBytes("0"))
    }

    @Test
    fun `small byte values`() {
        assertEquals("512 B", formatBytes("512"))
        assertEquals("1 B", formatBytes("1"))
    }

    @Test
    fun `kilobytes`() {
        assertEquals("1.00 KB", formatBytes("1024"))
        assertEquals("1.50 KB", formatBytes("1536"))
    }

    @Test
    fun `megabytes`() {
        assertEquals("1.00 MB", formatBytes("1048576"))
        assertEquals("5.25 MB", formatBytes("5505024"))
    }

    @Test
    fun `gigabytes`() {
        assertEquals("1.00 GB", formatBytes("1073741824"))
        assertEquals("2.50 GB", formatBytes("2684354560"))
    }

    @Test
    fun `terabytes`() {
        assertEquals("1.00 TB", formatBytes("1099511627776"))
    }

    @Test
    fun `large values use no decimals`() {
        // 150 GB = 161061273600
        assertEquals("150 GB", formatBytes("161061273600"))
    }

    @Test
    fun `mid-range values use one decimal`() {
        // 15.5 MB = 16252928
        assertEquals("15.5 MB", formatBytes("16252928"))
    }

    @Test
    fun `non-numeric string passes through unchanged`() {
        assertEquals("already formatted", formatBytes("already formatted"))
        assertEquals("1.5 GiB", formatBytes("1.5 GiB"))
    }

    @Test
    fun `negative value passes through unchanged`() {
        assertEquals("-1024", formatBytes("-1024"))
    }

    @Test
    fun `whitespace is trimmed before parsing`() {
        assertEquals("1.00 KB", formatBytes("  1024  "))
    }

    @Test
    fun `fractional bytes from API`() {
        assertEquals("1.50 KB", formatBytes("1536.0"))
    }
}
