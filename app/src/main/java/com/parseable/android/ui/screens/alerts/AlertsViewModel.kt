package com.parseable.android.ui.screens.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.model.Alert
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.repository.ParseableRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlertsState(
    val alerts: List<Alert> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val alertToDelete: Alert? = null,
)

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val repository: ParseableRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AlertsState())
    val state: StateFlow<AlertsState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        // Load once on creation; the screen no longer refreshes on every RESUME.
        refresh()
    }

    fun refresh() {
        // Cancel any in-flight load so out-of-order completions can't overwrite a newer alert
        // list with a stale one (init load, pull-to-refresh and deleteAlert->refresh can overlap).
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.listAlerts()) {
                is ApiResult.Success -> {
                    // Sort by the same field the UI shows (displayName = title ?: name), otherwise
                    // alerts that carry both fields, or a mix of per-stream and global formats,
                    // render in an order that doesn't match their visible labels.
                    val sorted = result.data.sortedBy { it.displayName.lowercase() }
                    _state.update { it.copy(alerts = sorted, isLoading = false) }
                }
                is ApiResult.Error -> {
                    _state.update { it.copy(isLoading = false, error = result.userMessage) }
                }
            }
        }
    }

    fun requestDelete(alert: Alert) {
        _state.update { it.copy(alertToDelete = alert) }
    }

    fun cancelDelete() {
        _state.update { it.copy(alertToDelete = null) }
    }

    private var deleteJob: Job? = null

    fun deleteAlert(alertId: String) {
        // Guard against a double-tap on the confirm button firing two concurrent DELETEs.
        if (deleteJob?.isActive == true) return
        deleteJob = viewModelScope.launch {
            _state.update { it.copy(alertToDelete = null, isLoading = true, error = null) }
            when (val result = repository.deleteAlert(alertId)) {
                is ApiResult.Success -> refresh()
                is ApiResult.Error -> {
                    _state.update { it.copy(isLoading = false, error = result.userMessage) }
                }
            }
        }
    }
}
