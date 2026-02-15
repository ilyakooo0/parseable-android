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
    if (bytes < 0) return raw
    if (bytes < 1024) return String.format(Locale.US, "%.0f B", bytes)
    var value = bytes
    var unitIndex = 0
    while (value >= 1024 && unitIndex < BYTE_UNITS.size - 1) {
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
