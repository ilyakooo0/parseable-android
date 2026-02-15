package com.parseable.android

import com.parseable.android.ui.screens.logviewer.formatTimestamp
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

class FormatTimestampTest {

    @Test
    fun `formats ISO8601 UTC timestamp`() {
        // Force UTC for test determinism
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val result = formatTimestamp("2024-12-15T14:30:45.123Z")
            assertEquals("Dec 15 14:30:45.123", result)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `formats Parseable timestamp format with +00 00 suffix`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val result = formatTimestamp("2024-12-15T14:30:45.123456+00:00")
            assertEquals("Dec 15 14:30:45.123", result)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `returns raw string for unparseable timestamp`() {
        val result = formatTimestamp("not-a-timestamp")
        assertEquals("not-a-timestamp", result)
    }

    @Test
    fun `handles empty string`() {
        val result = formatTimestamp("")
        assertEquals("", result)
    }

    @Test
    fun `converts to local timezone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val result = formatTimestamp("2024-06-15T18:30:45.000Z")
            // June = EDT = UTC-4
            assertEquals("Jun 15 14:30:45.000", result)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
