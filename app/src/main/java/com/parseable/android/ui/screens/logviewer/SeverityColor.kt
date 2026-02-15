package com.parseable.android.ui.screens.logviewer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Severity levels for log entries, ordered from most to least severe.
 */
enum class LogSeverity {
    FATAL,
    ERROR,
    WARNING,
    INFO,
    DEBUG,
    TRACE,
    UNKNOWN,
}

/**
 * Field names commonly used for severity/level across logging frameworks.
 * Checked in order of priority (case-insensitive).
 */
private val SEVERITY_FIELD_NAMES = listOf(
    "level",
    "severity",
    "loglevel",
    "log_level",
    "log.level",
    "priority",
    "verbosity",
    "p_level",
    "status",
    "syslog_severity",
    "log_severity",
    "msg_severity",
    "event_severity",
)

/**
 * Attempts to detect the severity level from a log entry by checking common field names.
 * Returns [LogSeverity.UNKNOWN] if no recognizable severity field or value is found.
 */
fun detectSeverity(logEntry: JsonObject): LogSeverity {
    for (fieldName in SEVERITY_FIELD_NAMES) {
        val entry = logEntry.entries.firstOrNull { it.key.equals(fieldName, ignoreCase = true) }
        if (entry != null) {
            val value = entry.value
            if (value is JsonPrimitive) {
                val parsed = parseSeverityValue(value.content, entry.key)
                if (parsed != LogSeverity.UNKNOWN) return parsed
            }
        }
    }
    return LogSeverity.UNKNOWN
}

private fun parseSeverityValue(raw: String, fieldName: String): LogSeverity {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return LogSeverity.UNKNOWN

    // Try numeric parsing first
    trimmed.toIntOrNull()?.let { num ->
        return when {
            // Syslog severity (0 = emergency .. 7 = debug)
            fieldName.equals("syslog_severity", ignoreCase = true) ||
                fieldName.equals("priority", ignoreCase = true) -> when (num) {
                0, 1, 2 -> LogSeverity.FATAL   // emergency, alert, critical
                3 -> LogSeverity.ERROR
                4 -> LogSeverity.WARNING
                5 -> LogSeverity.INFO           // notice
                6 -> LogSeverity.INFO           // informational
                7 -> LogSeverity.DEBUG
                else -> LogSeverity.UNKNOWN
            }
            // HTTP status codes
            fieldName.equals("status", ignoreCase = true) -> when {
                num >= 500 -> LogSeverity.ERROR
                num >= 400 -> LogSeverity.WARNING
                num in 200..399 -> LogSeverity.INFO
                else -> LogSeverity.UNKNOWN
            }
            // Generic numeric (Java util logging style: higher = more severe)
            else -> when {
                num >= 1000 -> LogSeverity.FATAL
                num >= 900 -> LogSeverity.ERROR
                num >= 800 -> LogSeverity.WARNING
                num >= 700 -> LogSeverity.INFO
                num >= 400 -> LogSeverity.DEBUG
                num > 0 -> LogSeverity.TRACE
                else -> LogSeverity.UNKNOWN
            }
        }
    }

    // String-based matching (case-insensitive)
    return when (trimmed.uppercase()) {
        "FATAL", "F", "CRIT", "CRITICAL", "EMERG", "EMERGENCY",
        "ALERT", "A", "PANIC",
        -> LogSeverity.FATAL

        "ERROR", "ERR", "E", "SEVERE", "FAILURE", "FAIL",
        -> LogSeverity.ERROR

        "WARN", "WARNING", "W",
        -> LogSeverity.WARNING

        "INFO", "I", "INFORMATION", "INFORMATIONAL", "NOTICE", "N",
        "OK", "SUCCESS",
        -> LogSeverity.INFO

        "DEBUG", "D", "FINE", "CONFIG", "FINER",
        -> LogSeverity.DEBUG

        "TRACE", "T", "V", "VERBOSE", "FINEST", "ALL",
        -> LogSeverity.TRACE

        else -> LogSeverity.UNKNOWN
    }
}

/**
 * Returns a subtle background tint color for the given severity level,
 * or `null` to use the default card color.
 */
@Composable
fun severityBackgroundColor(severity: LogSeverity): Color? {
    val isDark = isSystemInDarkTheme()
    return when (severity) {
        LogSeverity.FATAL -> if (isDark) Color(0xFF3A1820) else Color(0xFFFCE4EC)
        LogSeverity.ERROR -> if (isDark) Color(0xFF2D1A1E) else Color(0xFFFDECEC)
        LogSeverity.WARNING -> if (isDark) Color(0xFF2D2715) else Color(0xFFFFF8E1)
        LogSeverity.INFO -> if (isDark) Color(0xFF152535) else Color(0xFFE8F4FD)
        LogSeverity.DEBUG -> null
        LogSeverity.TRACE -> if (isDark) Color(0xFF1E1F25) else Color(0xFFF5F5F8)
        LogSeverity.UNKNOWN -> null
    }
}
