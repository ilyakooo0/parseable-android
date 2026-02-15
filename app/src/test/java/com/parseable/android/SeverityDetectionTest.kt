package com.parseable.android

import com.parseable.android.ui.screens.logviewer.LogSeverity
import com.parseable.android.ui.screens.logviewer.detectSeverity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SeverityDetectionTest {

    private fun logWith(vararg pairs: Pair<String, String>): JsonObject {
        return JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
    }

    // --- Field name detection ---

    @Test
    fun `detects level field`() {
        assertEquals(LogSeverity.ERROR, detectSeverity(logWith("level" to "error")))
    }

    @Test
    fun `detects severity field`() {
        assertEquals(LogSeverity.WARNING, detectSeverity(logWith("severity" to "warn")))
    }

    @Test
    fun `detects loglevel field`() {
        assertEquals(LogSeverity.INFO, detectSeverity(logWith("loglevel" to "info")))
    }

    @Test
    fun `detects log_level field`() {
        assertEquals(LogSeverity.DEBUG, detectSeverity(logWith("log_level" to "debug")))
    }

    @Test
    fun `field name matching is case-insensitive`() {
        assertEquals(LogSeverity.ERROR, detectSeverity(logWith("Level" to "ERROR")))
        assertEquals(LogSeverity.ERROR, detectSeverity(logWith("SEVERITY" to "error")))
        assertEquals(LogSeverity.ERROR, detectSeverity(logWith("LogLevel" to "ERR")))
    }

    @Test
    fun `returns UNKNOWN when no severity field present`() {
        assertEquals(LogSeverity.UNKNOWN, detectSeverity(logWith("message" to "hello", "host" to "server1")))
    }

    @Test
    fun `returns UNKNOWN for empty log entry`() {
        assertEquals(LogSeverity.UNKNOWN, detectSeverity(JsonObject(emptyMap())))
    }

    // --- String value matching ---

    @Test
    fun `matches fatal variants`() {
        for (value in listOf("fatal", "FATAL", "F", "crit", "critical", "emerg", "emergency", "alert", "panic")) {
            assertEquals("Expected FATAL for '$value'", LogSeverity.FATAL, detectSeverity(logWith("level" to value)))
        }
    }

    @Test
    fun `matches error variants`() {
        for (value in listOf("error", "ERROR", "err", "E", "severe", "failure", "fail")) {
            assertEquals("Expected ERROR for '$value'", LogSeverity.ERROR, detectSeverity(logWith("level" to value)))
        }
    }

    @Test
    fun `matches warning variants`() {
        for (value in listOf("warn", "WARNING", "W", "warning")) {
            assertEquals("Expected WARNING for '$value'", LogSeverity.WARNING, detectSeverity(logWith("level" to value)))
        }
    }

    @Test
    fun `matches info variants`() {
        for (value in listOf("info", "INFO", "I", "information", "informational", "notice", "ok", "success")) {
            assertEquals("Expected INFO for '$value'", LogSeverity.INFO, detectSeverity(logWith("level" to value)))
        }
    }

    @Test
    fun `matches debug variants`() {
        for (value in listOf("debug", "DEBUG", "D", "fine", "config", "finer")) {
            assertEquals("Expected DEBUG for '$value'", LogSeverity.DEBUG, detectSeverity(logWith("level" to value)))
        }
    }

    @Test
    fun `matches trace variants`() {
        for (value in listOf("trace", "TRACE", "T", "V", "verbose", "finest", "all")) {
            assertEquals("Expected TRACE for '$value'", LogSeverity.TRACE, detectSeverity(logWith("level" to value)))
        }
    }

    @Test
    fun `value matching is case-insensitive`() {
        assertEquals(LogSeverity.ERROR, detectSeverity(logWith("level" to "Error")))
        assertEquals(LogSeverity.WARNING, detectSeverity(logWith("level" to "WaRnInG")))
    }

    @Test
    fun `trims whitespace from values`() {
        assertEquals(LogSeverity.ERROR, detectSeverity(logWith("level" to "  error  ")))
    }

    @Test
    fun `returns UNKNOWN for unrecognized values`() {
        assertEquals(LogSeverity.UNKNOWN, detectSeverity(logWith("level" to "custom_level")))
    }

    // --- Numeric values ---

    @Test
    fun `syslog severity numeric values`() {
        assertEquals(LogSeverity.FATAL, detectSeverity(logWith("syslog_severity" to "0")))   // emergency
        assertEquals(LogSeverity.FATAL, detectSeverity(logWith("syslog_severity" to "1")))   // alert
        assertEquals(LogSeverity.FATAL, detectSeverity(logWith("syslog_severity" to "2")))   // critical
        assertEquals(LogSeverity.ERROR, detectSeverity(logWith("syslog_severity" to "3")))   // error
        assertEquals(LogSeverity.WARNING, detectSeverity(logWith("syslog_severity" to "4"))) // warning
        assertEquals(LogSeverity.INFO, detectSeverity(logWith("syslog_severity" to "5")))    // notice
        assertEquals(LogSeverity.INFO, detectSeverity(logWith("syslog_severity" to "6")))    // informational
        assertEquals(LogSeverity.DEBUG, detectSeverity(logWith("syslog_severity" to "7")))   // debug
    }

    @Test
    fun `HTTP status code mapping`() {
        assertEquals(LogSeverity.INFO, detectSeverity(logWith("status" to "200")))
        assertEquals(LogSeverity.INFO, detectSeverity(logWith("status" to "301")))
        assertEquals(LogSeverity.WARNING, detectSeverity(logWith("status" to "404")))
        assertEquals(LogSeverity.ERROR, detectSeverity(logWith("status" to "500")))
        assertEquals(LogSeverity.ERROR, detectSeverity(logWith("status" to "503")))
    }

    @Test
    fun `generic numeric level mapping`() {
        assertEquals(LogSeverity.FATAL, detectSeverity(logWith("level" to "1000")))
        assertEquals(LogSeverity.ERROR, detectSeverity(logWith("level" to "900")))
        assertEquals(LogSeverity.WARNING, detectSeverity(logWith("level" to "800")))
        assertEquals(LogSeverity.INFO, detectSeverity(logWith("level" to "700")))
        assertEquals(LogSeverity.DEBUG, detectSeverity(logWith("level" to "500")))
        assertEquals(LogSeverity.TRACE, detectSeverity(logWith("level" to "100")))
    }

    // --- Priority among fields ---

    @Test
    fun `level field takes priority over status`() {
        val log = JsonObject(mapOf(
            "level" to JsonPrimitive("error"),
            "status" to JsonPrimitive("200"),
        ))
        assertEquals(LogSeverity.ERROR, detectSeverity(log))
    }

    @Test
    fun `first matching field with recognized value wins`() {
        val log = JsonObject(mapOf(
            "level" to JsonPrimitive("unknown_value"),
            "severity" to JsonPrimitive("warn"),
        ))
        assertEquals(LogSeverity.WARNING, detectSeverity(log))
    }
}
