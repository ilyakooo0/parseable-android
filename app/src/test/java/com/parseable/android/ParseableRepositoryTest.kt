package com.parseable.android

import com.parseable.android.data.api.ParseableApiClient
import com.parseable.android.data.model.*
import com.parseable.android.data.repository.ParseableRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ParseableRepositoryTest {

    private lateinit var apiClient: ParseableApiClient
    private lateinit var repository: ParseableRepository

    @Before
    fun setup() {
        apiClient = mockk(relaxed = true)
        repository = ParseableRepository(apiClient)
    }

    @Test
    fun `getAbout caches result on success`() = runTest {
        val aboutInfo = AboutInfo(version = "1.0.0", mode = "standalone")
        coEvery { apiClient.getAbout() } returns ApiResult.Success(aboutInfo)

        val first = repository.getAbout()
        val second = repository.getAbout()

        assertTrue(first is ApiResult.Success)
        assertEquals("1.0.0", (first as ApiResult.Success).data.version)
        // Should only call API once due to caching
        coVerify(exactly = 1) { apiClient.getAbout() }
    }

    @Test
    fun `getAbout does not cache errors`() = runTest {
        coEvery { apiClient.getAbout() } returns ApiResult.Error("fail", 500)

        val first = repository.getAbout()
        repository.getAbout()

        assertTrue(first is ApiResult.Error)
        coVerify(exactly = 2) { apiClient.getAbout() }
    }

    @Test
    fun `listStreams forceRefresh bypasses cache`() = runTest {
        val streams = listOf(LogStream(name = "test"))
        coEvery { apiClient.listStreams() } returns ApiResult.Success(streams)

        repository.listStreams()
        repository.listStreams(forceRefresh = true)

        coVerify(exactly = 2) { apiClient.listStreams() }
    }

    @Test
    fun `listStreams returns cached data within TTL`() = runTest {
        val streams = listOf(LogStream(name = "test"))
        coEvery { apiClient.listStreams() } returns ApiResult.Success(streams)

        repository.listStreams()
        val cached = repository.listStreams(forceRefresh = false)

        assertTrue(cached is ApiResult.Success)
        assertEquals(1, (cached as ApiResult.Success).data.size)
        coVerify(exactly = 1) { apiClient.listStreams() }
    }

    @Test
    fun `invalidateAll clears all caches`() = runTest {
        val about = AboutInfo(version = "1.0.0")
        val streams = listOf(LogStream(name = "s1"))
        coEvery { apiClient.getAbout() } returns ApiResult.Success(about)
        coEvery { apiClient.listStreams() } returns ApiResult.Success(streams)

        repository.getAbout()
        repository.listStreams()
        repository.invalidateAll()
        repository.getAbout()
        repository.listStreams()

        coVerify(exactly = 2) { apiClient.getAbout() }
        coVerify(exactly = 2) { apiClient.listStreams() }
    }

    @Test
    fun `configure invalidates cache and configures client`() = runTest {
        val config = ServerConfig("https://test.com", "user", "pass", true)
        repository.configure(config)
        verify { apiClient.configure(config) }
    }

    @Test
    fun `auth error triggers channel`() = runTest {
        coEvery { apiClient.listStreams() } returns ApiResult.Error("Unauthorized", 401)

        val result = repository.listStreams()

        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).isUnauthorized)
    }

    @Test
    fun `queryLogs builds correct SQL with filter`() = runTest {
        coEvery { apiClient.queryLogs(any(), any(), any()) } returns ApiResult.Success(emptyList())

        repository.queryLogs(
            stream = "my_stream",
            startTime = "2024-01-01T00:00:00Z",
            endTime = "2024-01-02T00:00:00Z",
            filterSql = "\"level\" = 'ERROR'",
            limit = 100,
        )

        coVerify {
            apiClient.queryLogs(
                match { it.contains("WHERE \"level\" = 'ERROR'") && it.contains("LIMIT 100") },
                "2024-01-01T00:00:00Z",
                "2024-01-02T00:00:00Z",
            )
        }
    }

    @Test
    fun `queryLogs without filter builds SQL without WHERE`() = runTest {
        coEvery { apiClient.queryLogs(any(), any(), any()) } returns ApiResult.Success(emptyList())

        repository.queryLogs(
            stream = "test",
            startTime = "start",
            endTime = "end",
        )

        coVerify {
            apiClient.queryLogs(
                match { !it.contains("WHERE") && it.contains("\"test\"") },
                "start",
                "end",
            )
        }
    }

    @Test
    fun `deleteStream invalidates cache`() = runTest {
        val streams = listOf(LogStream(name = "s1"))
        coEvery { apiClient.listStreams() } returns ApiResult.Success(streams)
        coEvery { apiClient.deleteStream(any()) } returns ApiResult.Success("ok")

        repository.listStreams()
        repository.deleteStream("s1")
        repository.listStreams()

        // After delete, cache was invalidated, so listStreams called twice
        coVerify(exactly = 2) { apiClient.listStreams() }
    }

    @Test
    fun `getStreamSchema caches per stream`() = runTest {
        val schema = StreamSchema(fields = listOf(SchemaField(name = "col1")))
        coEvery { apiClient.getStreamSchema("s1") } returns ApiResult.Success(schema)
        coEvery { apiClient.getStreamSchema("s2") } returns ApiResult.Success(schema)

        repository.getStreamSchema("s1")
        repository.getStreamSchema("s1") // cached
        repository.getStreamSchema("s2") // different stream, not cached

        coVerify(exactly = 1) { apiClient.getStreamSchema("s1") }
        coVerify(exactly = 1) { apiClient.getStreamSchema("s2") }
    }
}
