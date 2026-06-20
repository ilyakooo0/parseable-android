package com.parseable.android.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pin to Locale.US so the MMM month token is always rendered as a stable English
// abbreviation (e.g. "Jun") regardless of the device's locale.
private val displayFormatter = DateTimeFormatter.ofPattern("MMM dd HH:mm:ss.SSS", Locale.US)

fun formatTimestamp(raw: String): String {
    // Fast path: an ISO-8601 instant in UTC (e.g. "2026-06-20T12:00:00Z").
    try {
        val instant = Instant.parse(raw)
        return ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()).format(displayFormatter)
    } catch (_: Exception) {
        // Fall through to the offset-aware parser.
    }

    // General path: any ISO-8601 timestamp carrying an explicit offset, including
    // "+00:00" and non-UTC offsets like "+05:30" that Instant.parse rejects.
    try {
        val offset = OffsetDateTime.parse(raw)
        return offset.atZoneSameInstant(ZoneId.systemDefault()).format(displayFormatter)
    } catch (_: Exception) {
        // Fall through to the naive (offset-less) parser.
    }

    // Final path: an offset-less ISO-8601 timestamp (e.g. "2026-06-20T12:00:00.000").
    // Parseable stores p_timestamp in UTC, so interpret a naive timestamp as UTC and
    // convert to the device's zone — otherwise these would render in UTC while
    // offset-carrying values render in local time, an inconsistent mix.
    try {
        val local = LocalDateTime.parse(raw)
        return local.atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(displayFormatter)
    } catch (_: Exception) {
        // Unparseable — show the server value verbatim rather than nothing.
    }

    return raw
}
