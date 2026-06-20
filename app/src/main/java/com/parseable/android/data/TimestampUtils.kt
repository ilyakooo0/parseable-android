package com.parseable.android.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pin to Locale.US so the MMM month token is always rendered as a stable English
// abbreviation (e.g. "Jun") regardless of the device's locale.
private val displayFormatter = DateTimeFormatter.ofPattern("MMM dd HH:mm:ss.SSS", Locale.US)

fun formatTimestamp(raw: String): String {
    return try {
        val instant = Instant.parse(raw)
        val local = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
        local.format(displayFormatter)
    } catch (_: Exception) {
        // Try alternate format with +00:00 suffix
        try {
            val normalized = raw.replace("+00:00", "Z")
            val instant = Instant.parse(normalized)
            val local = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
            local.format(displayFormatter)
        } catch (_: Exception) {
            raw
        }
    }
}
