package com.parseable.android

import com.parseable.android.data.model.AboutInfo
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.data.repository.SettingsRepository
import com.parseable.android.ui.screens.login.LoginViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private lateinit var repository: ParseableRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: LoginViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        coEvery { settingsRepository.serverConfig } returns flowOf(null)
        viewModel = LoginViewModel(repository, settingsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onLogin validates required fields`() {
        viewModel.onLogin()

        val state = viewModel.state.value
        assertNotNull(state.serverUrlError)
        assertNotNull(state.usernameError)
        assertNotNull(state.passwordError)
        assertFalse(state.isLoading)
    }

    @Test
    fun `onLogin validates URL only when others filled`() {
        viewModel.onServerUrlChange("")
        viewModel.onUsernameChange("admin")
        viewModel.onPasswordChange("password")
        viewModel.onLogin()

        val state = viewModel.state.value
        assertNotNull(state.serverUrlError)
        assertNull(state.usernameError)
        assertNull(state.passwordError)
    }

    @Test
    fun `onLogin succeeds with valid credentials`() = runTest {
        coEvery { repository.testConnection() } returns ApiResult.Success("ok")
        coEvery { repository.verifyServer() } returns ApiResult.Success(AboutInfo(version = "1.0"))

        viewModel.onServerUrlChange("https://test.parseable.com")
        viewModel.onUsernameChange("admin")
        viewModel.onPasswordChange("password")
        viewModel.onLogin()

        val state = viewModel.state.value
        assertTrue(state.loginSuccess)
        assertFalse(state.isLoading)
        coVerify { settingsRepository.saveServerConfig(any()) }
    }

    @Test
    fun `onLogin fails when connection fails`() = runTest {
        coEvery { repository.testConnection() } returns ApiResult.Error("Connection refused", 0)

        viewModel.onServerUrlChange("https://bad.server")
        viewModel.onUsernameChange("admin")
        viewModel.onPasswordChange("password")
        viewModel.onLogin()

        val state = viewModel.state.value
        assertFalse(state.loginSuccess)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("Connection failed"))
    }

    @Test
    fun `onLogin fails when about check fails`() = runTest {
        coEvery { repository.testConnection() } returns ApiResult.Success("ok")
        coEvery { repository.verifyServer() } returns ApiResult.Error("not parseable", 404)

        viewModel.onServerUrlChange("https://test.com")
        viewModel.onUsernameChange("admin")
        viewModel.onPasswordChange("password")
        viewModel.onLogin()

        val state = viewModel.state.value
        assertFalse(state.loginSuccess)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("doesn't appear to be Parseable"))
    }

    @Test
    fun `onLogin shows invalid credentials on 401`() = runTest {
        coEvery { repository.testConnection() } returns ApiResult.Success("ok")
        coEvery { repository.verifyServer() } returns ApiResult.Error("Unauthorized", 401)

        viewModel.onServerUrlChange("https://test.parseable.com")
        viewModel.onUsernameChange("admin")
        viewModel.onPasswordChange("wrongpass")
        viewModel.onLogin()

        val state = viewModel.state.value
        assertFalse(state.loginSuccess)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("Invalid credentials"))
        assertFalse(state.error!!.contains("doesn't appear to be Parseable"))
    }

    @Test
    fun `onServerUrlChange clears error`() {
        viewModel.onLogin() // triggers error
        assertNotNull(viewModel.state.value.serverUrlError)

        viewModel.onServerUrlChange("test")
        assertNull(viewModel.state.value.serverUrlError)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `onAllowInsecureChange updates state`() {
        assertFalse(viewModel.state.value.allowInsecure)
        viewModel.onAllowInsecureChange(true)
        assertTrue(viewModel.state.value.allowInsecure)
    }

    @Test
    fun `does not auto-populate password from saved config`() = runTest {
        val config = com.parseable.android.data.model.ServerConfig(
            serverUrl = "https://test.com",
            username = "admin",
            password = "secret",
            useTls = true,
        )
        coEvery { settingsRepository.serverConfig } returns flowOf(config)

        viewModel = LoginViewModel(repository, settingsRepository)

        val state = viewModel.state.value
        assertEquals("https://test.com", state.serverUrl)
        assertEquals("admin", state.username)
        assertEquals("", state.password) // Password should NOT be auto-populated
        assertTrue(state.hasSavedCredentials)
    }

    @Test
    fun `onLogin prepends https when not insecure`() = runTest {
        coEvery { repository.testConnection() } returns ApiResult.Success("ok")
        coEvery { repository.verifyServer() } returns ApiResult.Success(AboutInfo())

        viewModel.onServerUrlChange("test.parseable.com")
        viewModel.onUsernameChange("admin")
        viewModel.onPasswordChange("pass")
        viewModel.onLogin()

        coVerify {
            repository.configure(match { it.serverUrl == "https://test.parseable.com" })
        }
    }

    @Test
    fun `onLogin prepends http when insecure`() = runTest {
        coEvery { repository.testConnection() } returns ApiResult.Success("ok")
        coEvery { repository.verifyServer() } returns ApiResult.Success(AboutInfo())

        viewModel.onServerUrlChange("test.parseable.com")
        viewModel.onAllowInsecureChange(true)
        viewModel.onUsernameChange("admin")
        viewModel.onPasswordChange("pass")
        viewModel.onLogin()

        coVerify {
            repository.configure(match { it.serverUrl == "http://test.parseable.com" })
        }
    }

    @Test
    fun `onLogin handles uppercase URL scheme`() = runTest {
        coEvery { repository.testConnection() } returns ApiResult.Success("ok")
        coEvery { repository.verifyServer() } returns ApiResult.Success(AboutInfo())

        viewModel.onServerUrlChange("HTTPS://test.parseable.com")
        viewModel.onUsernameChange("admin")
        viewModel.onPasswordChange("pass")
        viewModel.onLogin()

        coVerify {
            repository.configure(match {
                it.serverUrl == "HTTPS://test.parseable.com" && it.useTls
            })
        }
    }

    @Test
    fun `onLogin handles mixed case URL scheme`() = runTest {
        coEvery { repository.testConnection() } returns ApiResult.Success("ok")
        coEvery { repository.verifyServer() } returns ApiResult.Success(AboutInfo())

        viewModel.onServerUrlChange("Http://test.parseable.com")
        viewModel.onAllowInsecureChange(true)
        viewModel.onUsernameChange("admin")
        viewModel.onPasswordChange("pass")
        viewModel.onLogin()

        coVerify {
            repository.configure(match {
                it.serverUrl == "Http://test.parseable.com" && !it.useTls
            })
        }
    }
}
