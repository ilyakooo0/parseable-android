package com.parseable.android.ui.screens.logviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.repository.ParseableRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

enum class TimeRange(val label: String, val minutes: Long) {
    LAST_5M("5m", 5),
    LAST_15M("15m", 15),
    LAST_30M("30m", 30),
    LAST_1H("1h", 60),
    LAST_6H("6h", 360),
    LAST_24H("24h", 1440),
    LAST_7D("7d", 10080),
    LAST_30D("30d", 43200),
}

data class LogViewerState(
    val streamName: String = "",
    val logs: List<JsonObject> = emptyList(),
    val columns: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedTimeRange: TimeRange = TimeRange.LAST_1H,
    val searchQuery: String = "",
    val activeFilters: List<String> = emptyList(),
    val filterClauses: List<String> = emptyList(),
    val customSql: String = "",
    val currentLimit: Int = 500,
    val hasMore: Boolean = false,
)

@HiltViewModel
class LogViewerViewModel @Inject constructor(
    private val repository: ParseableRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LogViewerState())
    val state: StateFlow<LogViewerState> = _state.asStateFlow()

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'")

    fun initialize(streamName: String) {
        if (_state.value.streamName == streamName) return
        _state.update { it.copy(streamName = streamName) }
        loadSchema(streamName)
        refresh()
    }

    private fun loadSchema(streamName: String) {
        viewModelScope.launch {
            when (val result = repository.getStreamSchema(streamName)) {
                is ApiResult.Success -> {
                    val columns = result.data.fields.map { it.name }
                    _state.update { it.copy(columns = columns) }
                }
                is ApiResult.Error -> { /* Schema loading failure is non-fatal */ }
            }
        }
    }

    fun onTimeRangeChange(range: TimeRange) {
        _state.update { it.copy(selectedTimeRange = range, currentLimit = 500) }
        refresh()
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        refresh()
    }

    fun addFilter(column: String, operator: String, value: String) {
        val clause = when (operator) {
            "IS NULL", "IS NOT NULL" -> "\"$column\" $operator"
            "LIKE", "ILIKE" -> "\"$column\" $operator '%$value%'"
            else -> "\"$column\" $operator '$value'"
        }
        val display = when (operator) {
            "IS NULL", "IS NOT NULL" -> "$column $operator"
            else -> "$column $operator $value"
        }
        _state.update {
            it.copy(
                filterClauses = it.filterClauses + clause,
                activeFilters = it.activeFilters + display,
            )
        }
        refresh()
    }

    fun removeFilter(display: String) {
        val index = _state.value.activeFilters.indexOf(display)
        if (index >= 0) {
            _state.update {
                it.copy(
                    activeFilters = it.activeFilters.toMutableList().apply { removeAt(index) },
                    filterClauses = it.filterClauses.toMutableList().apply { removeAt(index) },
                )
            }
            refresh()
        }
    }

    fun clearFilters() {
        _state.update { it.copy(activeFilters = emptyList(), filterClauses = emptyList()) }
        refresh()
    }

    fun executeCustomSql(sql: String) {
        _state.update { it.copy(customSql = sql) }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            val (startTime, endTime) = getTimeRange()
            when (val result = repository.queryLogsRaw(sql, startTime, endTime)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            logs = result.data,
                            isLoading = false,
                            hasMore = result.data.size >= it.currentLimit,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _state.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
            }
        }
    }

    fun refresh() {
        val current = _state.value
        if (current.streamName.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            val (startTime, endTime) = getTimeRange()

            // Build WHERE clause from filters + search
            val clauses = current.filterClauses.toMutableList()
            if (current.searchQuery.isNotBlank()) {
                // Search across all text columns
                clauses.add("CAST(* AS TEXT) ILIKE '%${current.searchQuery}%'")
            }

            val filterSql = clauses.joinToString(" AND ")

            when (val result = repository.queryLogs(
                stream = current.streamName,
                startTime = startTime,
                endTime = endTime,
                filterSql = filterSql,
                limit = current.currentLimit,
            )) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            logs = result.data,
                            isLoading = false,
                            hasMore = result.data.size >= it.currentLimit,
                        )
                    }
                }
                is ApiResult.Error -> {
                    // If the CAST(*) search fails, try without the global search
                    if (current.searchQuery.isNotBlank() && clauses.size == 1) {
                        retryWithSimpleSearch(current, startTime, endTime)
                    } else {
                        _state.update {
                            it.copy(isLoading = false, error = result.message)
                        }
                    }
                }
            }
        }
    }

    private suspend fun retryWithSimpleSearch(
        current: LogViewerState,
        startTime: String,
        endTime: String,
    ) {
        // Fall back to a simpler search: look in common log field names
        val searchFields = current.columns
            .filter { !it.startsWith("p_") }
            .take(5)

        val searchClause = if (searchFields.isNotEmpty()) {
            searchFields.joinToString(" OR ") { "\"$it\" ILIKE '%${current.searchQuery}%'" }
        } else {
            return
        }

        when (val result = repository.queryLogs(
            stream = current.streamName,
            startTime = startTime,
            endTime = endTime,
            filterSql = searchClause,
            limit = current.currentLimit,
        )) {
            is ApiResult.Success -> {
                _state.update {
                    it.copy(
                        logs = result.data,
                        isLoading = false,
                        hasMore = result.data.size >= it.currentLimit,
                    )
                }
            }
            is ApiResult.Error -> {
                _state.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun loadMore() {
        _state.update { it.copy(currentLimit = it.currentLimit + 500) }
        refresh()
    }

    private fun getTimeRange(): Pair<String, String> {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val start = now.minusMinutes(_state.value.selectedTimeRange.minutes)
        return Pair(
            start.format(dateFormatter),
            now.format(dateFormatter),
        )
    }
}
