package com.parseable.android.ui.screens.logviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.escapeIdentifier
import com.parseable.android.data.escapeLikePattern
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
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

/** The original (column, operator, value) a filter chip was built from. */
data class FilterCondition(
    val column: String,
    val operator: String,
    val value: String,
)

data class FilterState(
    val searchQuery: String = "",
    val activeFilters: List<String> = emptyList(),
    val filterClauses: List<String> = emptyList(),
    // Structured form of each chip, index-aligned with activeFilters/filterClauses.
    // Kept so saved filters reconstruct from real values instead of re-parsing the
    // human-readable display string (which is ambiguous when a value contains an operator).
    val filterConditions: List<FilterCondition> = emptyList(),
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
    // Upper time bound (epoch millis) fixed at the first page of a paging session, so loadMore()
    // doesn't slide a relative ("last N minutes") window forward as it re-queries. Null until the
    // first refresh of a session; re-anchored whenever currentLimit resets to the initial page.
    val pageAnchorTime: Long? = null,
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
    // True while the log list is scrolled to the top. New rows prepend at the top, so when the
    // user is already there they see them live and the "+N new" badge must stay at 0. The badge
    // only accumulates while the user is scrolled away, and resets when they return to the top.
    @Volatile private var viewingTop: Boolean = true

    // Identities of rows already surfaced during the current streaming session. Used to dedupe
    // re-fetched boundary rows. Kept INDEPENDENT of the displayed `logs` list — which is capped
    // at STREAMING_MAX_LOGS — so that under sustained high-volume streaming, boundary rows
    // evicted from the view are still recognized as duplicates and not prepended twice.
    // Insertion-ordered so the oldest keys are trimmed first. Touched only from the single
    // streaming coroutine and from startStreaming (which first cancels that coroutine).
    private val seenStreamingKeys = LinkedHashSet<String>()
    private var searchJob: Job? = null
    private var schemaJob: Job? = null
    private var refreshJob: Job? = null

    companion object {
        private const val STREAMING_BASE_INTERVAL_MS = 3000L
        private const val STREAMING_MAX_INTERVAL_MS = 30000L
        private const val STREAMING_MAX_LOGS = 1000
        // Remember more identities than the display cap so a row evicted from the view is still
        // deduped on a later re-fetch. 2x the display cap covers the worst realistic overlap.
        private const val SEEN_STREAMING_KEYS_MAX = STREAMING_MAX_LOGS * 2
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
                // Key on the full row content (same identity used for streaming dedup, see
                // logIdentity). Keying on only p_timestamp/p_metadata/p_tags collided for rows
                // sharing those fields but differing in body, so the positional tiebreaker made
                // keys shift when streaming prepended rows — corrupting expanded state and
                // letting remember(stableKey) blocks render a stale body. The trailing counter
                // only disambiguates genuinely identical rows, whose rendering is identical too.
                val base = log.toString()
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
        // A new query invalidates the live-tail baseline and the paged-up limit, matching
        // the time-range / custom-SQL handlers.
        stopStreaming()
        // Searching returns to the filter-builder query path, so drop any active custom SQL.
        _state.update { it.copy(currentLimit = 500, filters = it.filters.copy(searchQuery = query, customSql = "", isSearching = query.isNotBlank())) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(filters = it.filters.copy(isSearching = false)) }
            refresh()
        }
    }

    /**
     * Run the current search immediately (e.g. the IME "Search" action). Cancels the pending
     * debounce so it can't fire a second, redundant refresh ~300ms later.
     */
    fun submitSearch() {
        searchJob?.cancel()
        _state.update { it.copy(filters = it.filters.copy(isSearching = false)) }
        stopStreaming()
        refresh()
    }

    fun addFilter(column: String, operator: String, value: String) {
        addFilterInternal(column, operator, value)
        refresh()
    }

    private fun addFilterInternal(column: String, operator: String, value: String) {
        if (operator !in ALLOWED_OPERATORS) return
        val safeColumn = escapeIdentifier(column)
        val clause = when (operator) {
            "IS NULL", "IS NOT NULL" -> "\"$safeColumn\" $operator"
            // %/_ in the user's value must match literally, so escape the LIKE wildcards
            // and declare the escape character. Exact comparisons keep %/_ verbatim.
            "LIKE", "ILIKE" -> "\"$safeColumn\" $operator '%${escapeLikePattern(value)}%' ESCAPE '\\'"
            else -> "\"$safeColumn\" $operator '${escapeSql(value)}'"
        }
        // IS NULL / IS NOT NULL ignore the value in both the clause and the display, so store
        // an empty value in the structured condition too (keeps saved filters clean).
        val conditionValue = when (operator) {
            "IS NULL", "IS NOT NULL" -> ""
            else -> value
        }
        val display = when (operator) {
            "IS NULL", "IS NOT NULL" -> "$column $operator"
            else -> "$column $operator $value"
        }
        // A new filter invalidates the live-tail baseline and the paged-up limit.
        stopStreaming()
        _state.update {
            it.copy(
                currentLimit = 500,
                filters = it.filters.copy(
                    filterClauses = it.filters.filterClauses + clause,
                    activeFilters = it.filters.activeFilters + display,
                    filterConditions = it.filters.filterConditions + FilterCondition(column, operator, conditionValue),
                    customSql = "",
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
                    currentLimit = 500,
                    filters = it.filters.copy(
                        activeFilters = it.filters.activeFilters.filterIndexed { i, _ -> i != index },
                        filterClauses = it.filters.filterClauses.filterIndexed { i, _ -> i != index },
                        filterConditions = it.filters.filterConditions.filterIndexed { i, _ -> i != index },
                        customSql = "",
                    ),
                )
            } else {
                it
            }
        }
        if (removed) {
            // A changed filter set invalidates the live-tail baseline.
            stopStreaming()
            refresh()
        }
    }

    fun clearFilters() {
        stopStreaming()
        _state.update {
            it.copy(currentLimit = 500, filters = it.filters.copy(activeFilters = emptyList(), filterClauses = emptyList(), filterConditions = emptyList(), customSql = ""))
        }
        refresh()
    }

    fun executeCustomSql(sql: String) {
        // Drop a trailing statement terminator before we append anything — otherwise
        // "SELECT * FROM x;" would become "SELECT * FROM x; LIMIT 5000", a syntax error.
        val trimmed = sql.trim().trimEnd(';').trim()
        if (!trimmed.uppercase().startsWith("SELECT")) {
            _state.update { it.copy(error = "Only SELECT queries are allowed") }
            return
        }
        // Enforce a LIMIT to prevent OOM from unbounded queries. Only honor a *top-level*
        // `LIMIT <n>` (optionally with OFFSET) anchored at the end of the statement — a
        // LIMIT inside a subquery doesn't bound the outer result set, so matching it
        // anywhere would let an unbounded outer query slip past the safety limit. Anchoring
        // at the end also means a string literal like 'LIMIT EXCEEDED' can't disable it.
        val hasTopLevelLimit =
            Regex("(?i)\\bLIMIT\\s+\\d+(\\s+OFFSET\\s+\\d+)?\\s*$").containsMatchIn(trimmed)
        val safeSql = if (!hasTopLevelLimit) {
            "$trimmed LIMIT $MAX_LOAD_LIMIT"
        } else {
            trimmed
        }
        // Store the custom SQL and let refresh() run it. Keeping it in state means
        // pull-to-refresh / time-range changes re-run the custom query instead of
        // silently replacing it with the default filter query.
        _state.update { it.copy(filters = it.filters.copy(customSql = safeSql), currentLimit = 500) }
        refresh()
    }

    private fun buildSearchClause(searchableColumns: List<String>, searchQuery: String): String? {
        if (searchQuery.isBlank()) return null
        if (searchableColumns.isEmpty()) return null
        val safeSearch = escapeLikePattern(searchQuery)
        return searchableColumns.joinToString(" OR ") {
            "CAST(\"${escapeIdentifier(it)}\" AS VARCHAR) ILIKE '%$safeSearch%' ESCAPE '\\'"
        }
    }

    fun refresh() {
        if (_state.value.streamName.isEmpty()) return

        // Cancel any in-flight query so out-of-order completions can't overwrite
        // fresh results with stale ones (filter/search/time-range/loadMore all call refresh()).
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // Anchor the upper time bound on the first page of a paging session (currentLimit at
            // its initial value) and reuse it for subsequent loadMore() pages. Otherwise a relative
            // range would recompute "now" on every page in getTimeRange(), sliding the window and
            // duplicating/dropping rows on an actively-ingesting stream.
            val anchor = _state.value.let { s ->
                if (s.currentLimit <= 500) System.currentTimeMillis() else s.pageAnchorTime
            }
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    streaming = it.streaming.copy(streamingError = null),
                    pageAnchorTime = anchor,
                )
            }

            // Snapshot state inside the coroutine so we always see the latest filters/columns
            val current = _state.value
            val (startTime, endTime) = getTimeRange()

            // A custom SQL query owns the whole statement (its own LIMIT, columns, ordering),
            // so re-run it verbatim. It is not paginated via loadMore(), hence hasMore = false.
            if (current.filters.customSql.isNotBlank()) {
                when (val result = repository.queryLogsRaw(current.filters.customSql, startTime, endTime)) {
                    is ApiResult.Success -> {
                        _state.update { it.copy(logs = result.data, logKeys = computeLogKeys(result.data), isLoading = false, hasMore = false) }
                    }
                    is ApiResult.Error -> {
                        _state.update { it.copy(isLoading = false, error = result.userMessage) }
                    }
                }
                return@launch
            }

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

            // Over-fetch by one row so we can distinguish "exactly currentLimit rows exist"
            // (no more pages) from "the page is full and more may exist" without firing a
            // wasted extra query the next time the user scrolls to the bottom.
            val requestLimit = current.currentLimit + 1
            when (val result = repository.queryLogs(
                stream = current.streamName,
                startTime = startTime,
                endTime = endTime,
                filterSql = filterSql,
                limit = requestLimit,
            )) {
                is ApiResult.Success -> {
                    val hasMore = result.data.size > current.currentLimit && current.currentLimit < MAX_LOAD_LIMIT
                    val logs = if (result.data.size > current.currentLimit) {
                        result.data.take(current.currentLimit)
                    } else {
                        result.data
                    }
                    _state.update {
                        it.copy(logs = logs, logKeys = computeLogKeys(logs), isLoading = false, hasMore = hasMore)
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
        // While live-tailing, logs are prepended in memory; a refresh() here would replace
        // them with a fresh page and drop the streamed rows. Pagination resumes once stopped.
        if (current.streaming.isStreaming) return
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
        val now = current.pageAnchorTime
            ?.let { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneOffset.UTC) }
            ?: ZonedDateTime.now(ZoneOffset.UTC)
        val start = now.minusMinutes(current.selectedTimeRange.minutes)
        return Pair(
            start.format(dateFormatter),
            now.format(dateFormatter),
        )
    }

    /**
     * Report whether the log list is scrolled to the top. Clears the "+N new" streaming badge
     * when the user returns to the top, since prepended rows there are seen immediately.
     */
    fun setViewingTop(atTop: Boolean) {
        viewingTop = atTop
        if (atTop && _state.value.streaming.streamingNewCount != 0) {
            _state.update { it.copy(streaming = it.streaming.copy(streamingNewCount = 0)) }
        }
    }

    fun toggleStreaming() {
        if (_state.value.streaming.isStreaming) {
            stopStreaming()
        } else {
            startStreaming()
        }
    }

    private fun startStreaming() {
        // The poller builds its own default SELECT * query and cannot honor a custom SQL
        // projection/filter, so streaming custom-query results would prepend mismatched rows.
        // The UI disables the toggle in this state; guard here too in case it's reached anyway.
        if (_state.value.filters.customSql.isNotBlank()) return
        stopStreaming()
        consecutiveStreamingErrors = 0
        val generation = ++streamingGeneration
        _state.update {
            it.copy(streaming = StreamingState(isStreaming = true, streamingNewCount = 0))
        }

        // Seed the dedup set with the rows already on screen so an initial row sitting exactly
        // on the streaming baseline timestamp can't be re-fetched and duplicated.
        seenStreamingKeys.clear()
        _state.value.logs.forEach { seenStreamingKeys.add(logIdentity(it)) }
        trimSeenStreamingKeys()

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

    /**
     * Content-based identity for a log row, used to dedupe re-fetched boundary logs.
     * Uses the full row: two rows are duplicates only when every field matches, so
     * distinct logs that happen to share a timestamp/metadata/tags are not dropped.
     */
    private fun logIdentity(log: JsonObject): String = log.toString()

    /**
     * Parse a server-rendered p_timestamp (which may be ISO with 'Z', offset-carrying,
     * space-separated, or offset-less UTC) and reformat it to the canonical query format
     * (`dateFormatter`) so it's a valid inclusive startTime for the next poll. Returns null
     * if the value can't be parsed, so the caller can keep the previous boundary.
     */
    private fun normalizeBoundaryTimestamp(raw: String): String? {
        val isoLike = if (' ' in raw && 'T' !in raw) raw.replaceFirst(' ', 'T') else raw
        val instant = try {
            Instant.parse(isoLike)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(isoLike).toInstant()
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(isoLike).toInstant(ZoneOffset.UTC)
                } catch (_: Exception) {
                    return null
                }
            }
        }
        return instant.atOffset(ZoneOffset.UTC).format(dateFormatter)
    }

    /** Bound the dedup set, evicting the oldest identities first (insertion order). */
    private fun trimSeenStreamingKeys() {
        if (seenStreamingKeys.size <= SEEN_STREAMING_KEYS_MAX) return
        val iterator = seenStreamingKeys.iterator()
        var toRemove = seenStreamingKeys.size - SEEN_STREAMING_KEYS_MAX
        while (toRemove > 0 && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
            toRemove--
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
                // Parenthesize: the search clause is OR-joined and must not break the
                // precedence of the AND-joined filter clauses around it.
                clauses.add("($searchClause)")
            } else {
                // Schema not loaded yet, so we can't build the search filter. Skip this
                // poll rather than querying without it — otherwise we'd prepend rows that
                // don't match the active search into a filtered view. Mirrors refresh(),
                // which errors out instead of running an unfiltered query.
                _state.update {
                    it.copy(
                        streaming = it.streaming.copy(
                            streamingError = "Search unavailable: stream schema not loaded",
                        ),
                    )
                }
                return
            }
        }

        val whereClause = if (clauses.isNotEmpty()) " WHERE ${clauses.joinToString(" AND ")}" else ""
        val safeName = escapeIdentifier(current.streamName)
        // Fetch up to the in-memory display cap. With a small limit (e.g. 200), a burst of
        // more than that many logs in one interval would advance lastSeenTimestamp past the
        // un-fetched rows and drop them permanently. Capping at STREAMING_MAX_LOGS means we
        // never skip rows we'd actually keep (anything older is evicted by the cap anyway).
        val sql = "SELECT * FROM \"$safeName\"$whereClause ORDER BY p_timestamp DESC LIMIT $STREAMING_MAX_LOGS"

        when (val result = repository.queryLogsRaw(sql, startTime, endTime)) {
            is ApiResult.Success -> {
                val newLogs = result.data
                if (newLogs.isNotEmpty()) {
                    // Advance the last-seen boundary. Rows are ordered newest-first, so
                    // normally we jump to the newest. But a full page means the LIMIT may
                    // have capped off matching rows OLDER than the newest in this batch
                    // (possible once filters/search narrow a high-volume stream) — advancing
                    // to the newest would skip them forever. In that case advance only to the
                    // oldest row we did fetch, so the next poll re-queries forward and closes
                    // the gap (re-fetched rows are deduped below).
                    val fullPage = newLogs.size >= STREAMING_MAX_LOGS
                    val boundaryLog = if (fullPage) newLogs.lastOrNull() else newLogs.firstOrNull()
                    val boundaryTimestamp = try {
                        boundaryLog?.get("p_timestamp")?.jsonPrimitive?.content
                    } catch (_: Exception) {
                        null
                    }
                    // Re-canonicalize the server's p_timestamp before reusing it as the next
                    // query's startTime. The raw value can be space-separated or offset-less,
                    // which the query API may reject or misparse — that would stall live-tail
                    // after the first batch. If it can't be parsed, leave lastSeenTimestamp as-is
                    // (the inclusive startTime simply re-fetches the window; rows are deduped).
                    var normalized = boundaryTimestamp?.let { normalizeBoundaryTimestamp(it) }
                    // On a saturated (full) page the boundary is the OLDEST fetched row so the
                    // next poll re-queries forward and closes the LIMIT gap. But if that oldest
                    // row carries the SAME timestamp we're already at — e.g. a burst of more than
                    // STREAMING_MAX_LOGS rows sharing one millisecond, or the parse failing — the
                    // boundary wouldn't move and we'd re-query the identical window forever. Fall
                    // back to the NEWEST row's timestamp to guarantee forward progress; the
                    // skipped backlog would be evicted by the display cap anyway.
                    if (fullPage && (normalized == null || normalized == lastSeenTimestamp)) {
                        val newestTimestamp = try {
                            newLogs.firstOrNull()?.get("p_timestamp")?.jsonPrimitive?.content
                        } catch (_: Exception) {
                            null
                        }
                        val newestNormalized = newestTimestamp?.let { normalizeBoundaryTimestamp(it) }
                        if (newestNormalized != null && newestNormalized != lastSeenTimestamp) {
                            normalized = newestNormalized
                        }
                    }
                    if (normalized != null) {
                        lastSeenTimestamp = normalized
                    }

                    // The query's startTime is inclusive, so rows sitting on the previous
                    // boundary timestamp get re-fetched. Dedup against everything seen this
                    // session (independent of the display cap) to avoid duplicate rows and an
                    // inflated count. Computed BEFORE the state update — and the seen-set
                    // mutated here, not inside the update lambda, which may re-run.
                    val freshLogs = newLogs.filterNot { logIdentity(it) in seenStreamingKeys }
                    newLogs.forEach { seenStreamingKeys.add(logIdentity(it)) }
                    trimSeenStreamingKeys()

                    if (freshLogs.isEmpty()) {
                        _state.update { it.copy(streaming = it.streaming.copy(streamingError = null)) }
                    } else {
                        _state.update { state ->
                            // Prepend new logs, cap total — avoid full intermediate list allocation
                            val remaining = (STREAMING_MAX_LOGS - freshLogs.size).coerceAtLeast(0)
                            val capped = ArrayList<JsonObject>(freshLogs.size + remaining).apply {
                                addAll(freshLogs)
                                val oldLogs = state.logs
                                addAll(oldLogs.subList(0, remaining.coerceAtMost(oldLogs.size)))
                            }
                            state.copy(
                                logs = capped,
                                logKeys = computeLogKeys(capped),
                                streaming = state.streaming.copy(
                                    // Accumulate new rows while the user is scrolled away; reset
                                    // to 0 when they're at the top (where prepended rows are seen
                                    // live). The previous cap was against total list size, so the
                                    // badge saturated at the display cap and never cleared.
                                    streamingNewCount = if (viewingTop) {
                                        0
                                    } else {
                                        state.streaming.streamingNewCount + freshLogs.size
                                    },
                                    streamingError = null,
                                ),
                            )
                        }
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
                val ruleGroups = current.filters.filterConditions
                    .mapIndexed { index, condition ->
                        FilterRule(
                            id = "rule_$index",
                            field = condition.column,
                            value = condition.value,
                            operator = condition.operator,
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
                // Reset to a clean filter state first, then let executeCustomSql validate
                // the SQL and own the customSql state. Pre-seeding filters.customSql with
                // the raw, unvalidated query meant that if executeCustomSql rejected it
                // (e.g. a legacy non-SELECT saved filter, which returns early without
                // touching state) we were left wedged in custom-SQL mode with broken SQL
                // that every later refresh() would keep re-running.
                _state.update {
                    it.copy(
                        filters = FilterState(),
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

    override fun onCleared() {
        super.onCleared()
        schemaJob?.cancel()
        schemaJob = null
        searchJob?.cancel()
        searchJob = null
        stopStreaming()
    }
}
