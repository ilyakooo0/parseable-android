package com.parseable.android

import com.parseable.android.ui.screens.logviewer.LogViewerViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class EscapeSqlTest {

    @Test
    fun `escapeSql replaces single quotes`() {
        assertEquals("it''s", LogViewerViewModel.escapeSql("it's"))
    }

    @Test
    fun `escapeSql handles multiple single quotes`() {
        assertEquals("it''s a ''test''", LogViewerViewModel.escapeSql("it's a 'test'"))
    }

    @Test
    fun `escapeSql handles empty string`() {
        assertEquals("", LogViewerViewModel.escapeSql(""))
    }

    @Test
    fun `escapeSql handles string without quotes`() {
        assertEquals("hello world", LogViewerViewModel.escapeSql("hello world"))
    }

    @Test
    fun `escapeSql handles consecutive quotes`() {
        assertEquals("''''", LogViewerViewModel.escapeSql("''"))
    }

    @Test
    fun `escapeSql does not alter double quotes`() {
        assertEquals("say \"hello\"", LogViewerViewModel.escapeSql("say \"hello\""))
    }

    @Test
    fun `escapeSql handles backslash`() {
        assertEquals("path\\to\\file", LogViewerViewModel.escapeSql("path\\to\\file"))
    }

    @Test
    fun `escapeSql handles semicolons`() {
        assertEquals("; DROP TABLE users --", LogViewerViewModel.escapeSql("; DROP TABLE users --"))
    }

    @Test
    fun `escapeSql handles quote injection attempt`() {
        assertEquals("''; DROP TABLE users --", LogViewerViewModel.escapeSql("'; DROP TABLE users --"))
    }
}
