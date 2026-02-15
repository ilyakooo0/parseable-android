package com.parseable.android

import com.parseable.android.data.escapeIdentifier
import com.parseable.android.data.escapeSql
import org.junit.Assert.assertEquals
import org.junit.Test

class EscapeSqlTest {

    @Test
    fun `escapeSql replaces single quotes`() {
        assertEquals("it''s", escapeSql("it's"))
    }

    @Test
    fun `escapeSql handles multiple single quotes`() {
        assertEquals("it''s a ''test''", escapeSql("it's a 'test'"))
    }

    @Test
    fun `escapeSql handles empty string`() {
        assertEquals("", escapeSql(""))
    }

    @Test
    fun `escapeSql handles string without quotes`() {
        assertEquals("hello world", escapeSql("hello world"))
    }

    @Test
    fun `escapeSql handles consecutive quotes`() {
        assertEquals("''''", escapeSql("''"))
    }

    @Test
    fun `escapeSql does not alter double quotes`() {
        assertEquals("say \"hello\"", escapeSql("say \"hello\""))
    }

    @Test
    fun `escapeSql handles backslash`() {
        assertEquals("path\\to\\file", escapeSql("path\\to\\file"))
    }

    @Test
    fun `escapeSql handles semicolons`() {
        assertEquals("; DROP TABLE users --", escapeSql("; DROP TABLE users --"))
    }

    @Test
    fun `escapeSql handles quote injection attempt`() {
        assertEquals("''; DROP TABLE users --", escapeSql("'; DROP TABLE users --"))
    }

    @Test
    fun `escapeIdentifier doubles double quotes`() {
        assertEquals("foo\"\"bar", escapeIdentifier("foo\"bar"))
    }

    @Test
    fun `escapeIdentifier handles no quotes`() {
        assertEquals("my_stream", escapeIdentifier("my_stream"))
    }

    @Test
    fun `escapeIdentifier handles empty string`() {
        assertEquals("", escapeIdentifier(""))
    }

    @Test
    fun `escapeIdentifier handles consecutive double quotes`() {
        assertEquals("\"\"\"\"", escapeIdentifier("\"\""))
    }
}
