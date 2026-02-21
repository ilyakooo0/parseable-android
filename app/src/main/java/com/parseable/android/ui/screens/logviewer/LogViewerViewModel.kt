package com.parseable.android.ui.screens.logviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.escapeIdentifier
import com.parseable.android.data.escapeSql
import com.parseable.android.data.model.*
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

data class FilterState(
    val searchQuery: String = "",
    val activeFilters: List<String> = emptyList(),
    val filterClauses: List<String> = emptyList(),
    val customSql: String = "",
    val isSearching: Boolean = false,
)

data class StreamingState(
    val isStreaming: Boolean = false,
    val streamingNewCount: Int = 0,
    val streamingError: String? = null,
    val currentIntervalMs: Long = 3000L,
)

data class SavedFiltersState(
    val filters: List<SavedFilter> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

data class LogViewerState(
    val streamName: String = "",
    val logs: List<JsonObject> = emptyList(),
    val logKeys: List<String> = emptyList(),
    val columns: List<String> = emptyList(),
    val searchableColumns: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedTimeRange: TimeRange = TimeRange.LAST_1H,
    val filters: FilterState = FilterState(),
    val streaming: StreamingState = StreamingState(),
    val savedFilters: SavedFiltersState = SavedFiltersState(),
    val currentLimit: Int = 500,
    val hasMore: Boolean = false,
    val customStartTime: Long? = null,
    val customEndTime: Long? = null,
) {
    // Convenience accessors for backward compatibility with Screen
    val searchQuery: String get() = filters.searchQuery
    val activeFilters: List<String> get() = filters.activeFilters
    val filterClauses: List<String> get() = filters.filterClauses
    val customSql: String get() = filters.customSql
    val isStreaming: Boolean get() = streaming.isStreaming
    val streamingNewCount: Int get() = streaming.streamingNewCount
    val streamingError: String? get() = streaming.streamingError
    val currentIntervalMs: Long get() = streaming.currentIntervalMs
    val isSearching: Boolean get() = filters.isSearching
}

@HiltViewModel
class LogViewerViewModel @Inject constructor(
    private val repository: ParseableRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LogViewerState())
    val state: StateFlow<LogViewerState> = _state.asStateFlow()

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'")

    private var streamingJob: Job? = null
    @Volatile private var streamingGeneration: Int = 0
    @Volatile private var lastSeenTimestamp: String? = null
    @Volatile private var consecutiveStreamingErrors: Int = 0
    private var searchJob: Job? = null
    private var schemaJob: Job? = null

    companion object {
        private const val STREAMING_BASE_INTERVAL_MS = 3000L
        private const val STREAMING_MAX_INTERVAL_MS = 30000L
        private const val STREAMING_MAX_LOGS = 1000
        private const val MAX_LOAD_LIMIT = 5000
        private const val LOAD_MORE_INCREMENT = 500
        private const val MAX_STREAMING_ERRORS = 5
        private val ALLOWED_OPERATORS = setOf("=", "!=", "LIKE", "ILIKE", ">", "<", ">=", "<=", "IS NULL", "IS NOT NULL")

        /**
         * Produces deterministic keys for a list of log entries so that expanded state
         * survives list mutations (e.g., new logs prepended during streaming).
         */
        internal fun computeLogKeys(logs: List<JsonObject>): List<String> {
            val seen = mutableMapOf<String, Int>()
            return logs.map { log ->
                val ts = log["p_timestamp"]?.toString()
                val meta = log["p_metadata"]?.toString()
                val tag = log["p_tags"]?.toString()
                val base = if (ts != null) "$ts|${meta.orEmpty()}|${tag.orEmpty()}" else log.hashCode().toString()
                val count = seen.getOrDefault(base, 0)
                seen[base] = count + 1
                if (count == 0) base else "$base|$count"
            }
        }
    }

    fun initialize(streamName: String) {
        if (_state.value.streamName == streamName) return
        stopStreaming()
        _state.update { it.copy(streamName = streamName) }
        loadSchema(streamName)
        refresh()
    }

    private fun loadSchema(streamName: String) {
        schemaJob?.cancel()
        schemaJob = viewModelScope.launch {
            when (val result = repository.getStreamSchema(streamName)) {
                is ApiResult.Success -> {
                    val columns = result.data.fields.map { it.name }.sorted()
                    val searchable = columns.filter { !it.startsWith("p_") }
                    _state.update { it.copy(columns = columns, searchableColumns = searchable) }
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
        _state.update { it.copy(filters = it.filters.copy(searchQuery = query, isSearching = query.isNotBlank())) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(filters = it.filters.copy(isSearching = false)) }
            refresh()
        }
    }

    fun addFilter(column: String, operator: String, value: String) {
        addFilterInternal(column, operator, value)
        refresh()
    }

    private fun addFilterInternal(column: String, operator: String, value: String) {
        if (operator !in ALLOWED_OPERATORS) return
        val safeColumn = escapeIdentifier(column)
        val safeValue = escapeSql(value)
        val clause = when (operator) {
            "IS NULL", "IS NOT NULL" -> "\"$safeColumn\" $operator"
            "LIKE", "ILIKE" -> "\"$safeColumn\" $operator '%$safeValue%'"
            else -> "\"$safeColumn\" $operator '$safeValue'"
        }
        val display = when (operator) {
            "IS NULL", "IS NOT NULL" -> "$column $operator"
            else -> "$column $operator $value"
        }
        _state.update {
            it.copy(
                filters = it.filters.copy(
                    filterClauses = it.filters.filterClauses + clause,
                    activeFilters = it.filters.activeFilters + display,
                ),
            )
        }
    }

    fun removeFilter(display: String) {
        var removed = false
        _state.update {
            val index = it.filters.activeFilters.indexOf(display)
            if (index >= 0 && index < it.filters.filterClauses.size) {
                removed = true
                it.copy(
                    filters = it.filters.copy(
                        activeFilters = it.filters.activeFilters.filterIndexed { i, _ -> i != index },
                        filterClauses = it.filters.filterClauses.filterIndexed { i, _ -> i != index },
                    ),
                )
            } else {
                it
            }
        }
        if (removed) refresh()
    }

    fun clearFilters() {
        _state.update {
            it.copy(filters = it.filters.copy(activeFilters = emptyList(), filterClauses = emptyList()))
        }
        refresh()
    }

    fun executeCustomSql(sql: String) {
        val trimmed = sql.trim()
        if (!trimmed.uppercase().startsWith("SELECT")) {
            _state.update { it.copy(error = "Only SELECT queries are allowed") }
            return
        }
        // Enforce a LIMIT to prevent OOM from unbounded queries
        val safeSql = if (!trimmed.uppercase().contains("LIMIT")) {
            "$trimmed LIMIT $MAX_LOAD_LIMIT"
        } else {
            trimmed
        }
        _state.update { it.copy(filters = it.filters.copy(customSql = safeSql), currentLimit = 500) }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            val (startTime, endTime) = getTimeRange()
            when (val result = repository.queryLogsRaw(safeSql, startTime, endTime)) {
                is ApiResult.Success -> {
                    val keys = computeLogKeys(result.data)
                    _state.update {
                        it.copy(
                            logs = result.data,
                            logKeys = keys,
                            isLoading = false,
                            hasMore = result.data.size >= it.currentLimit,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _state.update {
                        it.copy(isLoading = false, error = result.userMessage)
                    }
                }
            }
        }
    }

    private fun buildSearchClause(searchableColumns: List<String>, searchQuery: String): String? {
        if (searchQuery.isBlank()) return null
        if (searchableColumns.isEmpty()) return null
        val safeSearch = escapeSql(searchQuery)
        return searchableColumns.joinToString(" OR ") {
            "CAST(\"${escapeIdentifier(it)}\" AS VARCHAR) ILIKE '%$safeSearch%'"
        }
    }

    fun refresh() {
        if (_state.value.streamName.isEmpty()) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    streaming = it.streaming.copy(streamingError = null),
                )
            }

            // Snapshot state inside the coroutine so we always see the latest filters/columns
            val current = _state.value
            val (startTime, endTime) = getTimeRange()

            // Build WHERE clause from filters + search
            val clauses = current.filters.filterClauses.toMutableList()
            if (current.filters.searchQuery.isNotBlank()) {
                val searchClause = buildSearchClause(current.searchableColumns, current.filters.searchQuery)
                if (searchClause != null) {
                    clauses.add("($searchClause)")
                } else {
                    _state.update {
                        it.copy(isLoading = false, error = "Search unavailable: stream schema not loaded")
                    }
                    return@launch
                }
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
                    val keys = computeLogKeys(result.data)
                    _state.update {
                        it.copy(
                            logs = result.data,
                            logKeys = keys,
                            isLoading = false,
                            hasMore = result.data.size >= it.currentLimit,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _state.update {
                        it.copy(isLoading = false, error = result.userMessage)
                    }
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.currentLimit >= MAX_LOAD_LIMIT) {
            _state.update { it.copy(hasMore = false) }
            return
        }
        val newLimit = (current.currentLimit + LOAD_MORE_INCREMENT).coerceAtMost(MAX_LOAD_LIMIT)
        _state.update { it.copy(currentLimit = newLimit) }
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
        if (_state.value.streaming.isStreaming) {
            stopStreaming()
        } else {
            startStreaming()
        }
    }

    private fun startStreaming() {
        stopStreaming()
        consecutiveStreamingErrors = 0
        val generation = ++streamingGeneration
        _state.update {
            it.copy(streaming = StreamingState(isStreaming = true, streamingNewCount = 0))
        }

        // Set the baseline timestamp to "now" so we only poll for new logs
        lastSeenTimestamp = ZonedDateTime.now(ZoneOffset.UTC).format(dateFormatter)

        streamingJob = viewModelScope.launch {
            while (isActive && streamingGeneration == generation) {
                pollNewLogs()
                // Exponential backoff on errors, normal interval on success
                val intervalMs = if (consecutiveStreamingErrors > 0) {
                    (STREAMING_BASE_INTERVAL_MS * (1L shl consecutiveStreamingErrors.coerceAtMost(4)))
                        .coerceAtMost(STREAMING_MAX_INTERVAL_MS)
                } else {
                    STREAMING_BASE_INTERVAL_MS
                }
                _state.update {
                    it.copy(streaming = it.streaming.copy(currentIntervalMs = intervalMs))
                }
                delay(intervalMs)
            }
        }
    }

    fun stopStreaming() {
        streamingGeneration++
        streamingJob?.cancel()
        streamingJob = null
        _state.update {
            it.copy(streaming = it.streaming.copy(isStreaming = false))
        }
    }

    fun dismissStreamingError() {
        _state.update {
            it.copy(streaming = it.streaming.copy(streamingError = null))
        }
    }

    private suspend fun pollNewLogs() {
        val current = _state.value
        if (current.streamName.isEmpty()) return

        val startTime = lastSeenTimestamp ?: return
        val endTime = ZonedDateTime.now(ZoneOffset.UTC).format(dateFormatter)

        // Build WHERE clause from active filters + search
        val clauses = current.filters.filterClauses.toMutableList()
        if (current.filters.searchQuery.isNotBlank()) {
            val searchClause = buildSearchClause(current.searchableColumns, current.filters.searchQuery)
            if (searchClause != null) {
                clauses.add("($searchClause)")
            }
        }

        val whereClause = if (clauses.isNotEmpty()) " WHERE ${clauses.joinToString(" AND ")}" else ""
        val safeName = escapeIdentifier(current.streamName)
        val sql = "SELECT * FROM \"$safeName\"$whereClause ORDER BY p_timestamp DESC LIMIT 200"

        when (val result = repository.queryLogsRaw(sql, startTime, endTime)) {
            is ApiResult.Success -> {
                val newLogs = result.data
                if (newLogs.isNotEmpty()) {
                    // Update the last seen timestamp to the most recent log
                    val newestTimestamp = try {
                        newLogs.firstOrNull()
                            ?.get("p_timestamp")?.jsonPrimitive?.content
                    } catch (_: Exception) {
                        null
                    }
                    if (newestTimestamp != null) {
                        lastSeenTimestamp = newestTimestamp
                    }

                    _state.update { state ->
                        // Prepend new logs, cap total — avoid full intermediate list allocation
                        val remaining = (STREAMING_MAX_LOGS - newLogs.size).coerceAtLeast(0)
                        val capped = ArrayList<JsonObject>(newLogs.size + remaining).apply {
                            addAll(newLogs)
                            val oldLogs = state.logs
                            addAll(oldLogs.subList(0, remaining.coerceAtMost(oldLogs.size)))
                        }
                        state.copy(
                            logs = capped,
                            logKeys = computeLogKeys(capped),
                            streaming = state.streaming.copy(
                                streamingNewCount = state.streaming.streamingNewCount + newLogs.size,
                                streamingError = null,
                            ),
                        )
                    }
                }
                consecutiveStreamingErrors = 0
            }
            is ApiResult.Error -> {
                consecutiveStreamingErrors++
                if (consecutiveStreamingErrors >= MAX_STREAMING_ERRORS) {
                    stopStreaming()
                    _state.update {
                        it.copy(error = "Streaming stopped after repeated errors: ${result.userMessage}")
                    }
                } else if (consecutiveStreamingErrors >= 3) {
                    _state.update {
                        it.copy(
                            streaming = it.streaming.copy(
                                streamingError = "Streaming interrupted: ${result.userMessage}. Retrying..."
                            ),
                        )
                    }
                }
            }
        }
    }

    // --- Saved Filters (synced with Parseable server) ---

    fun loadSavedFilters() {
        viewModelScope.launch {
            _state.update { it.copy(savedFilters = it.savedFilters.copy(isLoading = true, error = null)) }
            when (val result = repository.listFilters()) {
                is ApiResult.Success -> {
                    val streamFilters = result.data
                        .filter { it.streamName == _state.value.streamName }
                        .sortedBy { it.filterName.lowercase() }
                    _state.update {
                        it.copy(savedFilters = it.savedFilters.copy(filters = streamFilters, isLoading = false))
                    }
                }
                is ApiResult.Error -> {
                    _state.update {
                        it.copy(savedFilters = it.savedFilters.copy(isLoading = false, error = result.userMessage))
                    }
                }
            }
        }
    }

    fun saveCurrentFilter(name: String) {
        val current = _state.value
        if (current.streamName.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(savedFilters = it.savedFilters.copy(isSaving = true, error = null)) }

            val query = if (current.filters.customSql.isNotBlank()) {
                SavedFilterQuery(
                    filterType = "sql",
                    filterQuery = current.filters.customSql,
                )
            } else {
                val ruleGroups = current.filters.activeFilters.zip(current.filters.filterClauses)
                    .mapIndexed { index, (display, _) ->
                        // Parse display back to field/operator/value
                        val parts = parseFilterDisplay(display)
                        FilterRule(
                            id = "rule_$index",
                            field = parts.first,
                            value = parts.third,
                            operator = parts.second,
                        )
                    }
                val builder = if (ruleGroups.isNotEmpty()) {
                    FilterBuilder(
                        id = "root",
                        combinator = "and",
                        rules = listOf(
                            FilterRuleGroup(
                                id = "group_0",
                                combinator = "and",
                                rules = ruleGroups,
                            ),
                        ),
                    )
                } else null

                SavedFilterQuery(
                    filterType = if (current.filters.searchQuery.isNotBlank()) "search" else "filter",
                    filterQuery = current.filters.searchQuery.ifBlank { null },
                    filterBuilder = builder,
                )
            }

            val filter = SavedFilter(
                filterName = name,
                streamName = current.streamName,
                query = query,
            )

            when (val result = repository.createFilter(filter)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            savedFilters = it.savedFilters.copy(
                                isSaving = false,
                                filters = it.savedFilters.filters + result.data,
                            ),
                        )
                    }
                }
                is ApiResult.Error -> {
                    _state.update {
                        it.copy(savedFilters = it.savedFilters.copy(isSaving = false, error = result.userMessage))
                    }
                }
            }
        }
    }

    fun applySavedFilter(filter: SavedFilter) {
        stopStreaming()

        when (filter.query.filterType) {
            "sql" -> {
                val sql = filter.query.filterQuery ?: return
                _state.update {
                    it.copy(
                        filters = FilterState(customSql = sql),
                        currentLimit = 500,
                    )
                }
                executeCustomSql(sql)
            }
            "search" -> {
                val searchQuery = filter.query.filterQuery ?: ""
                _state.update {
                    it.copy(
                        filters = FilterState(searchQuery = searchQuery),
                        currentLimit = 500,
                    )
                }
                // Also apply builder rules if present
                applyBuilderRules(filter.query.filterBuilder)
                refresh()
            }
            else -> {
                _state.update {
                    it.copy(
                        filters = FilterState(),
                        currentLimit = 500,
                    )
                }
                applyBuilderRules(filter.query.filterBuilder)
                refresh()
            }
        }
    }

    private fun applyBuilderRules(builder: FilterBuilder?) {
        if (builder == null) return
        for (group in builder.rules) {
            for (rule in group.rules) {
                if (rule.field.isNotBlank()) {
                    addFilterInternal(rule.field, rule.operator, rule.value)
                }
            }
        }
    }

    fun deleteSavedFilter(filterId: String) {
        viewModelScope.launch {
            when (repository.deleteFilter(filterId)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            savedFilters = it.savedFilters.copy(
                                filters = it.savedFilters.filters.filter { f -> f.filterId != filterId },
                            ),
                        )
                    }
                }
                is ApiResult.Error -> { /* Silently ignore — will show on next reload */ }
            }
        }
    }

    /** Parse a display string like "column = value" back into (field, operator, value) */
    private fun parseFilterDisplay(display: String): Triple<String, String, String> {
        for (op in listOf("IS NOT NULL", "IS NULL", "ILIKE", "LIKE", "!=", ">=", "<=", "=", ">", "<")) {
            val idx = display.indexOf(" $op")
            if (idx >= 0) {
                val field = display.substring(0, idx)
                val value = display.substring(idx + op.length + 1).trim()
                return Triple(field, op, value)
            }
        }
        return Triple(display, "=", "")
    }

    override fun onCleared() {
        super.onCleared()
        schemaJob?.cancel()
        schemaJob = null
        searchJob?.cancel()
        searchJob = null
        stopStreaming()
    }
}
