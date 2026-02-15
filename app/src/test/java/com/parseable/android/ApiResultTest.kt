package com.parseable.android

import com.parseable.android.data.model.ApiResult
import org.junit.Assert.*
import org.junit.Test

class ApiResultTest {

    @Test
    fun `401 error produces session expired message`() {
        val error = ApiResult.Error("Unauthorized", code = 401)
        assertTrue(error.isUnauthorized)
        assertEquals("Session expired. Please log in again.", error.userMessage)
    }

    @Test
    fun `403 error produces permission denied message`() {
        val error = ApiResult.Error("Forbidden", code = 403)
        assertEquals("Permission denied.", error.userMessage)
    }

    @Test
    fun `404 error produces not found message`() {
        val error = ApiResult.Error("Not Found", code = 404)
        assertTrue(error.isNotFound)
        assertEquals("Resource not found. It may have been deleted.", error.userMessage)
    }

    @Test
    fun `429 error produces rate limit message`() {
        val error = ApiResult.Error("Too Many Requests", code = 429)
        assertEquals("Too many requests. Please wait and try again.", error.userMessage)
    }

    @Test
    fun `500 error produces server error message`() {
        val error = ApiResult.Error("Internal Server Error", code = 500)
        assertTrue(error.isServerError)
        assertEquals("Server error (500). Please try again later.", error.userMessage)
    }

    @Test
    fun `502 error produces server error message`() {
        val error = ApiResult.Error("Bad Gateway", code = 502)
        assertTrue(error.isServerError)
        assertEquals("Server error (502). Please try again later.", error.userMessage)
    }

    @Test
    fun `network timeout produces timeout message`() {
        val error = ApiResult.Error("connect timeout", code = 0)
        assertTrue(error.isNetworkError)
        assertEquals("Connection timed out. Check your network.", error.userMessage)
    }

    @Test
    fun `DNS resolution failure produces no internet message`() {
        val error = ApiResult.Error("Unable to resolve host \"example.com\"", code = 0)
        assertTrue(error.isNetworkError)
        assertEquals("No internet connection.", error.userMessage)
    }

    @Test
    fun `connection refused produces unreachable message`() {
        val error = ApiResult.Error("Connection refused", code = 0)
        assertTrue(error.isNetworkError)
        assertEquals("Server is unreachable.", error.userMessage)
    }

    @Test
    fun `generic network error includes original message`() {
        val error = ApiResult.Error("SSL handshake failed", code = 0)
        assertTrue(error.isNetworkError)
        assertEquals("Network error: SSL handshake failed", error.userMessage)
    }

    @Test
    fun `non-standard HTTP code returns raw message`() {
        val error = ApiResult.Error("Custom error from proxy", code = 418)
        assertEquals("Custom error from proxy", error.userMessage)
    }

    @Test
    fun `success result carries data`() {
        val result: ApiResult<String> = ApiResult.Success("hello")
        assertTrue(result is ApiResult.Success)
        assertEquals("hello", (result as ApiResult.Success).data)
    }
}
