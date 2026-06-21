package com.parseable.android.data

import java.util.Locale

private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

/**
 * Converts a raw byte-count string (e.g. "123456789") to a human-readable
 * size (e.g. "117.7 MB"). If the string is not a valid number, it is
 * returned unchanged so pre-formatted values from the API pass through.
 */
fun formatBytes(raw: String?): String? {
    if (raw == null) return null
    val bytes = raw.trim().toDoubleOrNull() ?: return raw
    // toDoubleOrNull accepts "NaN"/"Infinity"; those slip past the range guards below and
    // would format as "NaN B" / "Infinity PB". Pass them through unchanged instead.
    if (!bytes.isFinite()) return raw
    if (bytes < 0) return raw
    if (bytes < 1024) return String.format(Locale.US, "%.0f B", bytes)
    var value = bytes
    var unitIndex = 0
    while (value >= 1024 && unitIndex < BYTE_UNITS.size - 1) {
        value /= 1024
        unitIndex++
    }
    // A value just under 1024 (e.g. 1023.999 KB) is formatted with "%.0f", which rounds it to
    // "1024 KB" — a unit that should never display. Promote to the next unit so it renders as
    // "1.00 MB". The "%.1f"/"%.2f" branches operate far below 1024, so only this case crosses.
    if (value >= 1023.5 && unitIndex < BYTE_UNITS.size - 1) {
        value /= 1024
        unitIndex++
    }
    return if (value >= 100) {
        String.format(Locale.US, "%.0f %s", value, BYTE_UNITS[unitIndex])
    } else if (value >= 10) {
        String.format(Locale.US, "%.1f %s", value, BYTE_UNITS[unitIndex])
    } else {
        String.format(Locale.US, "%.2f %s", value, BYTE_UNITS[unitIndex])
    }
}
