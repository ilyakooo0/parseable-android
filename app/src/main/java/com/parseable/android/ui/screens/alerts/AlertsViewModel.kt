package com.parseable.android.ui.screens.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.model.Alert
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.repository.ParseableRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.listAlerts()) {
                is ApiResult.Success -> {
                    val sorted = result.data.sortedBy { a ->
                        (a.name ?: a.title ?: "").lowercase()
                    }
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

    fun deleteAlert(alertId: String) {
        viewModelScope.launch {
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
