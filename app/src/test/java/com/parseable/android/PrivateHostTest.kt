package com.parseable.android

import com.parseable.android.ui.screens.login.extractHost
import com.parseable.android.ui.screens.login.isPrivateHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateHostTest {

    @Test
    fun `extractHost strips scheme port and path`() {
        assertEquals("192.168.1.5", extractHost("http://192.168.1.5:8000/path"))
        assertEquals("example.com", extractHost("https://example.com"))
        assertEquals("example.com", extractHost("example.com:9000"))
    }

    @Test
    fun `extractHost handles bracketed ipv6`() {
        assertEquals("::1", extractHost("http://[::1]:8000"))
        assertEquals("fe80::1", extractHost("[fe80::1]"))
    }

    @Test
    fun `extractHost leaves bare ipv6 intact`() {
        assertEquals("fe80::1", extractHost("fe80::1"))
    }

    @Test
    fun `private ipv4 ranges are detected`() {
        assertTrue(isPrivateHost("10.0.0.1"))
        assertTrue(isPrivateHost("127.0.0.1"))
        assertTrue(isPrivateHost("192.168.1.10"))
        assertTrue(isPrivateHost("172.16.0.1"))
        assertTrue(isPrivateHost("172.31.255.255"))
    }

    @Test
    fun `public ipv4 in the 172 gap is not private`() {
        assertFalse(isPrivateHost("172.15.0.1"))
        assertFalse(isPrivateHost("172.32.0.1"))
        assertFalse(isPrivateHost("8.8.8.8"))
    }

    @Test
    fun `public hostnames sharing a private prefix are not private`() {
        // The old prefix-string check wrongly classified these as private, hiding the
        // plaintext-HTTP warning.
        assertFalse(isPrivateHost("10.example.com"))
        assertFalse(isPrivateHost("192.168.evil.com"))
    }

    @Test
    fun `localhost is private`() {
        assertTrue(isPrivateHost("localhost"))
        assertTrue(isPrivateHost("api.localhost"))
        assertTrue(isPrivateHost(""))
    }

    @Test
    fun `ipv6 loopback and local ranges are private`() {
        assertTrue(isPrivateHost("::1"))
        assertTrue(isPrivateHost("fe80::1"))
        assertTrue(isPrivateHost("fd00::1"))
        assertTrue(isPrivateHost("fc00::1"))
    }

    @Test
    fun `public ipv6 is not private`() {
        assertFalse(isPrivateHost("2001:4860:4860::8888"))
    }
}
