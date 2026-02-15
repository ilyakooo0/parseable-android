package com.parseable.android.ui.screens.logviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.repository.ParseableRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    CUSTOM("Custom", 0),
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
    val isStreaming: Boolean = false,
    val streamingNewCount: Int = 0,
    val streamingError: String? = null,
    val customStartTime: Long? = null,
    val customEndTime: Long? = null,
)

@HiltViewModel
class LogViewerViewModel @Inject constructor(
    private val repository: ParseableRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LogViewerState())
    val state: StateFlow<LogViewerState> = _state.asStateFlow()

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'")

    private var streamingJob: Job? = null
    private var lastSeenTimestamp: String? = null
    private var consecutiveStreamingErrors: Int = 0
    private var searchJob: Job? = null

    companion object {
        private const val STREAMING_INTERVAL_MS = 3000L
        private const val STREAMING_MAX_LOGS = 5000

        /** Escape single quotes in user input to prevent SQL injection. */
        fun escapeSql(value: String): String = value.replace("'", "''")
    }

    fun initialize(streamName: String) {
        if (_state.value.streamName == streamName) return
        stopStreaming()
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
        stopStreaming()
        _state.update {
            it.copy(
                selectedTimeRange = range,
                currentLimit = 500,
                customStartTime = if (range != TimeRange.CUSTOM) null else it.customStartTime,
                customEndTime = if (range != TimeRange.CUSTOM) null else it.customEndTime,
            )
        }
        if (range != TimeRange.CUSTOM) refresh()
    }

    fun setCustomTimeRange(startMillis: Long, endMillis: Long) {
        stopStreaming()
        _state.update {
            it.copy(
                selectedTimeRange = TimeRange.CUSTOM,
                customStartTime = startMillis,
                customEndTime = endMillis,
                currentLimit = 500,
            )
        }
        refresh()
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            refresh()
        }
    }

    fun addFilter(column: String, operator: String, value: String) {
        val safeValue = escapeSql(value)
        val clause = when (operator) {
            "IS NULL", "IS NOT NULL" -> "\"$column\" $operator"
            "LIKE", "ILIKE" -> "\"$column\" $operator '%$safeValue%'"
            else -> "\"$column\" $operator '$safeValue'"
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
                val safeSearch = escapeSql(current.searchQuery)
                // Search across all text columns
                clauses.add("CAST(* AS TEXT) ILIKE '%$safeSearch%'")
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
                    // If query failed and we used CAST(*), retry with column-based search
                    if (current.searchQuery.isNotBlank()) {
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

        val safeSearch = escapeSql(current.searchQuery)
        val searchClause = if (searchFields.isNotEmpty()) {
            searchFields.joinToString(" OR ") { "\"$it\" ILIKE '%$safeSearch%'" }
        } else {
            return
        }

        // Combine existing filter clauses with the column-based search
        val retryClauses = current.filterClauses.toMutableList()
        retryClauses.add("($searchClause)")
        val filterSql = retryClauses.joinToString(" AND ")

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
        val current = _state.value
        if (current.selectedTimeRange == TimeRange.CUSTOM &&
            current.customStartTime != null && current.customEndTime != null
        ) {
            val start = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(current.customStartTime), ZoneOffset.UTC
            )
            val end = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(current.customEndTime), ZoneOffset.UTC
            )
            return Pair(start.format(dateFormatter), end.format(dateFormatter))
        }
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val start = now.minusMinutes(current.selectedTimeRange.minutes)
        return Pair(
            start.format(dateFormatter),
            now.format(dateFormatter),
        )
    }

    fun toggleStreaming() {
        if (_state.value.isStreaming) {
            stopStreaming()
        } else {
            startStreaming()
        }
    }

    private fun startStreaming() {
        stopStreaming()
        consecutiveStreamingErrors = 0
        _state.update { it.copy(isStreaming = true, streamingNewCount = 0, streamingError = null) }

        // Set the baseline timestamp to "now" so we only poll for new logs
        lastSeenTimestamp = ZonedDateTime.now(ZoneOffset.UTC).format(dateFormatter)

        streamingJob = viewModelScope.launch {
            while (isActive) {
                delay(STREAMING_INTERVAL_MS)
                pollNewLogs()
            }
        }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        _state.update { it.copy(isStreaming = false, streamingNewCount = 0, streamingError = null) }
    }

    private suspend fun pollNewLogs() {
        val current = _state.value
        if (current.streamName.isEmpty()) return

        val startTime = lastSeenTimestamp ?: return
        val endTime = ZonedDateTime.now(ZoneOffset.UTC).format(dateFormatter)

        // Build WHERE clause from active filters + search
        val clauses = current.filterClauses.toMutableList()
        if (current.searchQuery.isNotBlank()) {
            val safeSearch = escapeSql(current.searchQuery)
            val searchFields = current.columns.filter { !it.startsWith("p_") }.take(5)
            if (searchFields.isNotEmpty()) {
                clauses.add(
                    searchFields.joinToString(" OR ") {
                        "\"$it\" ILIKE '%$safeSearch%'"
                    }
                )
            }
        }

        val whereClause = if (clauses.isNotEmpty()) " WHERE ${clauses.joinToString(" AND ")}" else ""
        val sql = "SELECT * FROM \"${current.streamName}\"$whereClause ORDER BY p_timestamp DESC LIMIT 200"

        when (val result = repository.queryLogsRaw(sql, startTime, endTime)) {
            is ApiResult.Success -> {
                val newLogs = result.data
                if (newLogs.isNotEmpty()) {
                    // Update the last seen timestamp to the most recent log
                    val newestTimestamp = try {
                        newLogs.firstOrNull()
                            ?.get("p_timestamp")?.jsonPrimitive?.content
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    if (newestTimestamp != null) {
                        lastSeenTimestamp = newestTimestamp
                    }

                    _state.update { state ->
                        // Prepend new logs, cap total
                        val combined = newLogs + state.logs
                        val capped = if (combined.size > STREAMING_MAX_LOGS) {
                            combined.take(STREAMING_MAX_LOGS)
                        } else {
                            combined
                        }
                        state.copy(
                            logs = capped,
                            streamingNewCount = state.streamingNewCount + newLogs.size,
                            streamingError = null,
                        )
                    }
                }
                consecutiveStreamingErrors = 0
            }
            is ApiResult.Error -> {
                consecutiveStreamingErrors++
                if (consecutiveStreamingErrors >= 3) {
                    _state.update {
                        it.copy(streamingError = "Streaming interrupted: ${result.message}")
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
        searchJob = null
        stopStreaming()
    }
}
