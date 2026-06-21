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
    // Parseable/DataFusion sometimes renders p_timestamp with a space between the date and
    // time ("2026-06-20 12:00:00.000") instead of the ISO 'T', and may append a zone/offset
    // after another space ("... +00:00", "... UTC"). Normalize both so the parsers below
    // accept them; values already in canonical ISO form are left untouched.
    var iso = raw.trim()
    // Convert the FIRST space (date↔time separator) to 'T', unless a 'T' already precedes it
    // (in which case that space sits before a zone token, handled next).
    val firstSpace = iso.indexOf(' ')
    if (firstSpace != -1 && 'T' !in iso.substring(0, firstSpace + 1)) {
        iso = iso.substring(0, firstSpace) + "T" + iso.substring(firstSpace + 1)
    }
    // Drop a space sitting between the time and a trailing offset/zone token:
    // "...12:00:00 +00:00" → "...12:00:00+00:00", "...12:00:00 UTC" → "...12:00:00UTC".
    iso = iso.replace(Regex("(?<=\\d)\\s+(?=[+\\-Z]|UTC)"), "")
    // Map a textual "UTC" suffix to the ISO 'Z' the parsers understand.
    if (iso.endsWith("UTC")) iso = iso.removeSuffix("UTC") + "Z"

    // Fast path: an ISO-8601 instant in UTC (e.g. "2026-06-20T12:00:00Z").
    try {
        val instant = Instant.parse(iso)
        return ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()).format(displayFormatter)
    } catch (_: Exception) {
        // Fall through to the offset-aware parser.
    }

    // General path: any ISO-8601 timestamp carrying an explicit offset, including
    // "+00:00" and non-UTC offsets like "+05:30" that Instant.parse rejects.
    try {
        val offset = OffsetDateTime.parse(iso)
        return offset.atZoneSameInstant(ZoneId.systemDefault()).format(displayFormatter)
    } catch (_: Exception) {
        // Fall through to the naive (offset-less) parser.
    }

    // Final path: an offset-less ISO-8601 timestamp (e.g. "2026-06-20T12:00:00.000").
    // Parseable stores p_timestamp in UTC, so interpret a naive timestamp as UTC and
    // convert to the device's zone — otherwise these would render in UTC while
    // offset-carrying values render in local time, an inconsistent mix.
    try {
        val local = LocalDateTime.parse(iso)
        return local.atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(displayFormatter)
    } catch (_: Exception) {
        // Unparseable — show the server value verbatim rather than nothing.
    }

    return raw
}
