package com.parseable.android

import com.parseable.android.data.model.*
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.data.repository.SettingsRepository
import com.parseable.android.ui.screens.streams.StreamsViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamsViewModelTest {

    private lateinit var repository: ParseableRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: StreamsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh loads streams and about info`() = runTest {
        val streams = listOf(LogStream("stream1"), LogStream("stream2"))
        val about = AboutInfo(version = "1.0.0")
        coEvery { repository.listStreams(forceRefresh = true) } returns ApiResult.Success(streams)
        coEvery { repository.getAbout() } returns ApiResult.Success(about)
        coEvery { repository.getStreamStats(any()) } returns ApiResult.Error("skip", 0)

        viewModel = StreamsViewModel(repository, settingsRepository)
        viewModel.refresh()

        val state = viewModel.state.value
        assertEquals(2, state.streams.size)
        assertEquals("stream1", state.streams[0].name)
        assertEquals("1.0.0", state.aboutInfo?.version)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `refresh sets error when streams fail`() = runTest {
        coEvery { repository.listStreams(forceRefresh = true) } returns ApiResult.Error("Network error", 0)
        coEvery { repository.getAbout() } returns ApiResult.Error("fail", 0)

        viewModel = StreamsViewModel(repository, settingsRepository)
        viewModel.refresh()

        val state = viewModel.state.value
        assertTrue(state.streams.isEmpty())
        assertNotNull(state.error)
        assertFalse(state.isLoading)
    }

    @Test
    fun `refresh loads stats for each stream`() = runTest {
        val streams = listOf(LogStream("s1"), LogStream("s2"))
        coEvery { repository.listStreams(forceRefresh = true) } returns ApiResult.Success(streams)
        coEvery { repository.getAbout() } returns ApiResult.Success(AboutInfo())
        coEvery { repository.getStreamStats("s1") } returns ApiResult.Success(
            StreamStats(
                ingestion = IngestionStats(count = 100, size = "1MB"),
                storage = StorageStats(size = "500KB"),
            )
        )
        coEvery { repository.getStreamStats("s2") } returns ApiResult.Error("fail", 500)

        viewModel = StreamsViewModel(repository, settingsRepository)
        viewModel.refresh()

        val state = viewModel.state.value
        assertEquals(1, state.streamStats.size)
        assertNotNull(state.streamStats["s1"])
        assertEquals(100L, state.streamStats["s1"]?.eventCount)
        assertTrue(state.failedStats.contains("s2"))
    }

    @Test
    fun `logout calls clearConfig`() = runTest {
        coEvery { repository.listStreams(forceRefresh = true) } returns ApiResult.Success(emptyList())
        coEvery { repository.getAbout() } returns ApiResult.Success(AboutInfo())

        viewModel = StreamsViewModel(repository, settingsRepository)
        viewModel.logout()

        coVerify { settingsRepository.clearConfig() }
    }
}
