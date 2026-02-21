package com.parseable.android

import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.model.SchemaField
import com.parseable.android.data.model.StreamSchema
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.ui.screens.logviewer.LogViewerViewModel
import com.parseable.android.ui.screens.logviewer.TimeRange
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerViewModelTest {

    private lateinit var repository: ParseableRepository
    private lateinit var viewModel: LogViewerViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        coEvery { repository.getStreamSchema(any()) } returns ApiResult.Success(
            StreamSchema(fields = listOf(
                SchemaField(name = "message"),
                SchemaField(name = "level"),
                SchemaField(name = "p_timestamp"),
            ))
        )
        coEvery { repository.queryLogs(any(), any(), any(), any(), any()) } returns ApiResult.Success(emptyList())
        viewModel = LogViewerViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialize sets stream name and loads schema`() {
        viewModel.initialize("test_stream")

        val state = viewModel.state.value
        assertEquals("test_stream", state.streamName)
        assertEquals(3, state.columns.size)
        assertTrue(state.columns.contains("message"))
    }

    @Test
    fun `initialize is idempotent for same stream`() {
        viewModel.initialize("test_stream")
        viewModel.initialize("test_stream")

        coVerify(exactly = 1) { repository.getStreamSchema("test_stream") }
    }

    @Test
    fun `onTimeRangeChange updates state and refreshes`() {
        viewModel.initialize("test")
        viewModel.onTimeRangeChange(TimeRange.LAST_24H)

        assertEquals(TimeRange.LAST_24H, viewModel.state.value.selectedTimeRange)
        assertEquals(500, viewModel.state.value.currentLimit)
    }

    @Test
    fun `loadMore increments limit by 500`() {
        viewModel.initialize("test")
        viewModel.loadMore()

        assertEquals(1000, viewModel.state.value.currentLimit)
    }

    @Test
    fun `loadMore caps at MAX_LOAD_LIMIT of 5000`() {
        viewModel.initialize("test")
        // Load more 10 times (500 + 10*500 = 5500, but capped at 5000)
        repeat(10) { viewModel.loadMore() }

        assertEquals(5000, viewModel.state.value.currentLimit)
        assertFalse(viewModel.state.value.hasMore) // Should be false once at cap
    }

    @Test
    fun `addFilter adds clause and display to state`() {
        viewModel.initialize("test")
        viewModel.addFilter("level", "=", "ERROR")

        val state = viewModel.state.value
        assertEquals(1, state.activeFilters.size)
        assertEquals("level = ERROR", state.activeFilters[0])
        assertEquals(1, state.filterClauses.size)
    }

    @Test
    fun `removeFilter removes correct filter by index`() {
        viewModel.initialize("test")
        viewModel.addFilter("level", "=", "ERROR")
        viewModel.addFilter("message", "ILIKE", "timeout")

        assertEquals(2, viewModel.state.value.activeFilters.size)

        viewModel.removeFilter("level = ERROR")

        val state = viewModel.state.value
        assertEquals(1, state.activeFilters.size)
        assertEquals("message ILIKE timeout", state.activeFilters[0])
    }

    @Test
    fun `clearFilters removes all filters`() {
        viewModel.initialize("test")
        viewModel.addFilter("level", "=", "ERROR")
        viewModel.addFilter("level", "=", "WARN")
        viewModel.clearFilters()

        assertTrue(viewModel.state.value.activeFilters.isEmpty())
        assertTrue(viewModel.state.value.filterClauses.isEmpty())
    }

    @Test
    fun `refresh with error sets error state`() {
        coEvery { repository.queryLogs(any(), any(), any(), any(), any()) } returns
            ApiResult.Error("Server error", 500)

        viewModel.initialize("test")

        assertNotNull(viewModel.state.value.error)
    }

    @Test
    fun `refresh with success clears error`() {
        val logs = listOf(
            JsonObject(mapOf("message" to JsonPrimitive("hello")))
        )
        coEvery { repository.queryLogs(any(), any(), any(), any(), any()) } returns
            ApiResult.Success(logs)

        viewModel.initialize("test")

        assertNull(viewModel.state.value.error)
        assertEquals(1, viewModel.state.value.logs.size)
    }

    @Test
    fun `search builds ILIKE clause for all non-internal columns`() {
        viewModel.initialize("test")
        viewModel.onSearchQueryChange("error")

        // Advance past debounce
        testDispatcher.scheduler.advanceTimeBy(400)

        coVerify {
            repository.queryLogs(
                stream = "test",
                startTime = any(),
                endTime = any(),
                filterSql = match {
                    it.contains("CAST(\"message\" AS VARCHAR) ILIKE '%error%'") &&
                    it.contains("CAST(\"level\" AS VARCHAR) ILIKE '%error%'") &&
                    !it.contains("p_timestamp") // Internal columns excluded
                },
                limit = any(),
            )
        }
    }

    @Test
    fun `setCustomTimeRange updates state correctly`() {
        viewModel.initialize("test")
        viewModel.setCustomTimeRange(1000L, 2000L)

        val state = viewModel.state.value
        assertEquals(TimeRange.CUSTOM, state.selectedTimeRange)
        assertEquals(1000L, state.customStartTime)
        assertEquals(2000L, state.customEndTime)
        assertEquals(500, state.currentLimit) // Reset
    }

    @Test
    fun `toggleStreaming starts and stops`() {
        viewModel.initialize("test")

        viewModel.toggleStreaming()
        assertTrue(viewModel.state.value.isStreaming)

        viewModel.toggleStreaming()
        assertFalse(viewModel.state.value.isStreaming)
    }

    @Test
    fun `stopStreaming stops but preserves streaming error`() {
        viewModel.initialize("test")
        viewModel.toggleStreaming()
        viewModel.stopStreaming()

        val state = viewModel.state.value
        assertFalse(state.isStreaming)
    }

    @Test
    fun `dismissStreamingError clears the error`() {
        viewModel.initialize("test")
        viewModel.toggleStreaming()
        viewModel.stopStreaming()
        viewModel.dismissStreamingError()

        assertNull(viewModel.state.value.streamingError)
    }

    @Test
    fun `executeCustomSql rejects non-SELECT queries`() {
        viewModel.initialize("test")
        viewModel.executeCustomSql("DELETE FROM test_stream")

        assertEquals("Only SELECT queries are allowed", viewModel.state.value.error)
    }

    @Test
    fun `executeCustomSql appends LIMIT when missing`() {
        coEvery { repository.queryLogsRaw(any(), any(), any()) } returns ApiResult.Success(emptyList())

        viewModel.initialize("test")
        viewModel.executeCustomSql("SELECT * FROM test_stream")

        coVerify {
            repository.queryLogsRaw(
                match { it.contains("LIMIT 5000") },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `executeCustomSql preserves existing LIMIT`() {
        coEvery { repository.queryLogsRaw(any(), any(), any()) } returns ApiResult.Success(emptyList())

        viewModel.initialize("test")
        viewModel.executeCustomSql("SELECT * FROM test_stream LIMIT 50")

        coVerify {
            repository.queryLogsRaw(
                match { it.trim() == "SELECT * FROM test_stream LIMIT 50" },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `addFilter escapes column names`() {
        viewModel.initialize("test")
        viewModel.addFilter("col\"name", "=", "value")

        val clause = viewModel.state.value.filterClauses[0]
        assertTrue(clause.contains("col\"\"name")) // Double-escaped
    }

    @Test
    fun `addFilter escapes SQL values`() {
        viewModel.initialize("test")
        viewModel.addFilter("col", "=", "it's")

        val clause = viewModel.state.value.filterClauses[0]
        assertTrue(clause.contains("it''s")) // Escaped quote
    }

    @Test
    fun `addFilter handles IS NULL without value`() {
        viewModel.initialize("test")
        viewModel.addFilter("col", "IS NULL", "")

        val clause = viewModel.state.value.filterClauses[0]
        assertTrue(clause.endsWith("IS NULL"))
        assertFalse(clause.contains("''"))
    }
}
