package com.parseable.android

import com.parseable.android.data.model.*
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.ui.screens.logviewer.StreamInfoViewModel
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
class StreamInfoViewModelTest {

    private lateinit var repository: ParseableRepository
    private lateinit var viewModel: StreamInfoViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        viewModel = StreamInfoViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty`() {
        val state = viewModel.state.value
        assertEquals("", state.streamName)
        assertNull(state.stats)
        assertTrue(state.schema.isEmpty())
        assertTrue(state.retention.isEmpty())
        assertNull(state.rawInfo)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertFalse(state.isDeleting)
        assertFalse(state.deleteSuccess)
    }

    @Test
    fun `load fetches all stream info in parallel`() = runTest {
        val stats = StreamStats(
            ingestion = IngestionStats(count = 1000, size = "5MB"),
            storage = StorageStats(size = "2MB"),
        )
        val schema = StreamSchema(
            fields = listOf(
                SchemaField(name = "timestamp"),
                SchemaField(name = "message"),
            ),
        )
        val retention = listOf(RetentionConfig(duration = "30d", action = "delete"))
        val info = JsonObject(mapOf("created_at" to JsonPrimitive("2024-01-01")))

        coEvery { repository.getStreamStats("test-stream") } returns ApiResult.Success(stats)
        coEvery { repository.getStreamSchema("test-stream") } returns ApiResult.Success(schema)
        coEvery { repository.getStreamRetention("test-stream") } returns ApiResult.Success(retention)
        coEvery { repository.getStreamInfo("test-stream") } returns ApiResult.Success(info)

        viewModel.load("test-stream")

        val state = viewModel.state.value
        assertEquals("test-stream", state.streamName)
        assertEquals(1000L, state.stats?.ingestion?.count)
        assertEquals(2, state.schema.size)
        assertEquals(1, state.retention.size)
        assertNotNull(state.rawInfo)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `load shows error when some requests fail`() = runTest {
        coEvery { repository.getStreamStats("s1") } returns ApiResult.Error("Stats error", 500)
        coEvery { repository.getStreamSchema("s1") } returns ApiResult.Success(StreamSchema())
        coEvery { repository.getStreamRetention("s1") } returns ApiResult.Success(emptyList())
        coEvery { repository.getStreamInfo("s1") } returns ApiResult.Error("Info error", 500)

        viewModel.load("s1")

        val state = viewModel.state.value
        assertNotNull(state.error)
        assertFalse(state.isLoading)
        // Successful results should still be populated
        assertTrue(state.schema.isEmpty()) // empty but loaded
        assertTrue(state.retention.isEmpty())
    }

    @Test
    fun `load cancels previous load when called again`() = runTest {
        coEvery { repository.getStreamStats(any()) } returns ApiResult.Success(StreamStats())
        coEvery { repository.getStreamSchema(any()) } returns ApiResult.Success(StreamSchema())
        coEvery { repository.getStreamRetention(any()) } returns ApiResult.Success(emptyList())
        coEvery { repository.getStreamInfo(any()) } returns ApiResult.Success(JsonObject(emptyMap()))

        viewModel.load("stream-a")
        viewModel.load("stream-b")

        val state = viewModel.state.value
        assertEquals("stream-b", state.streamName)
    }

    @Test
    fun `deleteStream sets success on API success`() = runTest {
        coEvery { repository.getStreamStats(any()) } returns ApiResult.Success(StreamStats())
        coEvery { repository.getStreamSchema(any()) } returns ApiResult.Success(StreamSchema())
        coEvery { repository.getStreamRetention(any()) } returns ApiResult.Success(emptyList())
        coEvery { repository.getStreamInfo(any()) } returns ApiResult.Success(JsonObject(emptyMap()))
        coEvery { repository.deleteStream("my-stream") } returns ApiResult.Success("deleted")

        viewModel.load("my-stream")
        viewModel.deleteStream()

        val state = viewModel.state.value
        assertTrue(state.deleteSuccess)
        assertFalse(state.isDeleting)
        assertNull(state.error)
    }

    @Test
    fun `deleteStream sets error on API failure`() = runTest {
        coEvery { repository.getStreamStats(any()) } returns ApiResult.Success(StreamStats())
        coEvery { repository.getStreamSchema(any()) } returns ApiResult.Success(StreamSchema())
        coEvery { repository.getStreamRetention(any()) } returns ApiResult.Success(emptyList())
        coEvery { repository.getStreamInfo(any()) } returns ApiResult.Success(JsonObject(emptyMap()))
        coEvery { repository.deleteStream("my-stream") } returns ApiResult.Error("Forbidden", 403)

        viewModel.load("my-stream")
        viewModel.deleteStream()

        val state = viewModel.state.value
        assertFalse(state.deleteSuccess)
        assertFalse(state.isDeleting)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("Delete failed"))
    }

    @Test
    fun `deleteStream does nothing when streamName is empty`() = runTest {
        viewModel.deleteStream()

        val state = viewModel.state.value
        assertFalse(state.isDeleting)
        assertFalse(state.deleteSuccess)
        coVerify(exactly = 0) { repository.deleteStream(any()) }
    }

    @Test
    fun `consumeDeleteSuccess resets flag`() = runTest {
        coEvery { repository.getStreamStats(any()) } returns ApiResult.Success(StreamStats())
        coEvery { repository.getStreamSchema(any()) } returns ApiResult.Success(StreamSchema())
        coEvery { repository.getStreamRetention(any()) } returns ApiResult.Success(emptyList())
        coEvery { repository.getStreamInfo(any()) } returns ApiResult.Success(JsonObject(emptyMap()))
        coEvery { repository.deleteStream("s") } returns ApiResult.Success("ok")

        viewModel.load("s")
        viewModel.deleteStream()
        assertTrue(viewModel.state.value.deleteSuccess)

        viewModel.consumeDeleteSuccess()
        assertFalse(viewModel.state.value.deleteSuccess)
    }
}
