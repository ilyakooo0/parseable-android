package com.parseable.android

import com.parseable.android.data.model.AboutInfo
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.model.ServerConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.data.repository.SettingsRepository
import com.parseable.android.ui.screens.settings.SettingsViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var repository: ParseableRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = mockk(relaxed = true)
        repository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load populates server config from settings`() = runTest {
        val config = ServerConfig(
            serverUrl = "https://logs.example.com",
            username = "admin",
            password = "secret",
            useTls = true,
        )
        coEvery { settingsRepository.serverConfig } returns flowOf(config)
        coEvery { repository.getAbout() } returns ApiResult.Success(AboutInfo(version = "1.2.0"))
        coEvery { repository.listUsers() } returns ApiResult.Success(emptyList())

        val viewModel = SettingsViewModel(settingsRepository, repository)

        val state = viewModel.state.value
        assertEquals("https://logs.example.com", state.serverUrl)
        assertEquals("admin", state.username)
        assertTrue(state.useTls)
        assertFalse(state.isLoading)
    }

    @Test
    fun `load sets about info on success`() = runTest {
        val about = AboutInfo(version = "1.2.0", mode = "standalone", license = buildJsonObject { put("name", "AGPL") })
        coEvery { settingsRepository.serverConfig } returns flowOf(null)
        coEvery { repository.getAbout() } returns ApiResult.Success(about)
        coEvery { repository.listUsers() } returns ApiResult.Success(emptyList())

        val viewModel = SettingsViewModel(settingsRepository, repository)

        val state = viewModel.state.value
        assertEquals("1.2.0", state.aboutInfo?.version)
        assertEquals("standalone", state.aboutInfo?.mode)
        assertNull(state.error)
    }

    @Test
    fun `load parses user list from json objects`() = runTest {
        val users = listOf(
            JsonObject(mapOf("id" to JsonPrimitive("user1"))),
            JsonObject(mapOf("username" to JsonPrimitive("user2"))),
        )
        coEvery { settingsRepository.serverConfig } returns flowOf(null)
        coEvery { repository.getAbout() } returns ApiResult.Success(AboutInfo())
        coEvery { repository.listUsers() } returns ApiResult.Success(users)

        val viewModel = SettingsViewModel(settingsRepository, repository)

        val state = viewModel.state.value
        assertEquals(listOf("user1", "user2"), state.users)
        assertFalse(state.isLoading)
    }

    @Test
    fun `load sets error when about fails`() = runTest {
        coEvery { settingsRepository.serverConfig } returns flowOf(null)
        coEvery { repository.getAbout() } returns ApiResult.Error("Server error", 500)
        coEvery { repository.listUsers() } returns ApiResult.Success(emptyList())

        val viewModel = SettingsViewModel(settingsRepository, repository)

        val state = viewModel.state.value
        assertNotNull(state.error)
        assertNull(state.aboutInfo)
        assertFalse(state.isLoading)
    }

    @Test
    fun `load sets error when users fail`() = runTest {
        coEvery { settingsRepository.serverConfig } returns flowOf(null)
        coEvery { repository.getAbout() } returns ApiResult.Success(AboutInfo())
        coEvery { repository.listUsers() } returns ApiResult.Error("Forbidden", 403)

        val viewModel = SettingsViewModel(settingsRepository, repository)

        val state = viewModel.state.value
        assertNotNull(state.error)
        assertTrue(state.users.isEmpty())
    }

    @Test
    fun `load consolidates errors from both about and users`() = runTest {
        coEvery { settingsRepository.serverConfig } returns flowOf(null)
        coEvery { repository.getAbout() } returns ApiResult.Error("Server error", 500)
        coEvery { repository.listUsers() } returns ApiResult.Error("Forbidden", 403)

        val viewModel = SettingsViewModel(settingsRepository, repository)

        val state = viewModel.state.value
        assertNotNull(state.error)
        // Both error messages should be present
        assertTrue(state.error!!.contains("Server error"))
        assertTrue(state.error!!.contains("Permission denied"))
    }

    @Test
    fun `load resets isLoading on unexpected exception`() = runTest {
        coEvery { settingsRepository.serverConfig } returns flowOf(null)
        coEvery { repository.getAbout() } throws RuntimeException("unexpected")
        coEvery { repository.listUsers() } returns ApiResult.Success(emptyList())

        val viewModel = SettingsViewModel(settingsRepository, repository)

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("unexpected"))
    }

    @Test
    fun `load can be called again to refresh`() = runTest {
        coEvery { settingsRepository.serverConfig } returns flowOf(null)
        coEvery { repository.getAbout() } returns ApiResult.Error("fail", 500)
        coEvery { repository.listUsers() } returns ApiResult.Success(emptyList())

        val viewModel = SettingsViewModel(settingsRepository, repository)
        assertNotNull(viewModel.state.value.error)

        // Fix the mock and reload
        coEvery { repository.getAbout() } returns ApiResult.Success(AboutInfo(version = "2.0"))
        viewModel.load()

        val state = viewModel.state.value
        assertNull(state.error)
        assertEquals("2.0", state.aboutInfo?.version)
    }
}
