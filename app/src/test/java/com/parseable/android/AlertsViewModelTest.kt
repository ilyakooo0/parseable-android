package com.parseable.android

import com.parseable.android.data.model.Alert
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.ui.screens.alerts.AlertsViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsViewModelTest {

    private lateinit var repository: ParseableRepository
    private lateinit var viewModel: AlertsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        viewModel = AlertsViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty alerts and no loading`() {
        val state = viewModel.state.value
        assertTrue(state.alerts.isEmpty())
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertNull(state.alertToDelete)
    }

    @Test
    fun `refresh loads alerts on success`() = runTest {
        val alerts = listOf(
            Alert(id = "1", name = "Alert 1", stream = "stream1"),
            Alert(id = "2", name = "Alert 2", stream = "stream2"),
        )
        coEvery { repository.listAlerts() } returns ApiResult.Success(alerts)

        viewModel.refresh()

        val state = viewModel.state.value
        assertEquals(alerts, state.alerts)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `refresh shows error on failure`() = runTest {
        coEvery { repository.listAlerts() } returns ApiResult.Error("Server error", 500)

        viewModel.refresh()

        val state = viewModel.state.value
        assertTrue(state.alerts.isEmpty())
        assertFalse(state.isLoading)
        assertNotNull(state.error)
    }

    @Test
    fun `requestDelete sets alertToDelete`() {
        val alert = Alert(id = "1", name = "Alert 1", stream = "stream1")

        viewModel.requestDelete(alert)

        val state = viewModel.state.value
        assertEquals(alert, state.alertToDelete)
    }

    @Test
    fun `cancelDelete clears alertToDelete`() {
        val alert = Alert(id = "1", name = "Alert 1", stream = "stream1")
        viewModel.requestDelete(alert)
        assertEquals(alert, viewModel.state.value.alertToDelete)

        viewModel.cancelDelete()

        assertNull(viewModel.state.value.alertToDelete)
    }

    @Test
    fun `deleteAlert on success refreshes list`() = runTest {
        val alerts = listOf(
            Alert(id = "2", name = "Alert 2", stream = "stream2"),
        )
        coEvery { repository.deleteAlert("1") } returns ApiResult.Success("deleted")
        coEvery { repository.listAlerts() } returns ApiResult.Success(alerts)

        viewModel.deleteAlert("1")

        val state = viewModel.state.value
        assertEquals(alerts, state.alerts)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertNull(state.alertToDelete)
        coVerify { repository.deleteAlert("1") }
        coVerify { repository.listAlerts() }
    }

    @Test
    fun `deleteAlert on error shows error message`() = runTest {
        coEvery { repository.deleteAlert("1") } returns ApiResult.Error("Not found", 404)

        viewModel.deleteAlert("1")

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertNull(state.alertToDelete)
    }
}
