package com.parseable.android.ui.screens.logviewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import androidx.core.content.FileProvider
import com.parseable.android.data.formatTimestamp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    streamName: String,
    onBack: () -> Unit,
    onStreamInfo: (String) -> Unit,
    viewModel: LogViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSqlSheet by remember { mutableStateOf(false) }
    var expandedLogKey by remember { mutableStateOf<String?>(null) }
    var showDateRangePicker by remember { mutableStateOf(false) }
    var showShareWarning by remember { mutableStateOf(false) }

    LaunchedEffect(streamName) {
        viewModel.initialize(streamName)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = streamName,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (state.isStreaming) {
                                "Live - ${state.logs.size} logs (+${state.streamingNewCount} new)"
                            } else {
                                "${state.logs.size} results"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.isStreaming) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    StreamingToggleButton(
                        isStreaming = state.isStreaming,
                        onClick = viewModel::toggleStreaming,
                    )
                    IconButton(onClick = { onStreamInfo(streamName) }) {
                        Icon(Icons.Filled.Info, contentDescription = "Stream Info")
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filters")
                    }
                    IconButton(onClick = { showSqlSheet = true }) {
                        Icon(Icons.Filled.Code, contentDescription = "SQL Query")
                    }
                    IconButton(
                        onClick = { showShareWarning = true },
                        enabled = state.logs.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Share logs")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Time range chips
            TimeRangeBar(
                selectedRange = state.selectedTimeRange,
                onRangeSelected = { range ->
                    if (range == TimeRange.CUSTOM) {
                        showDateRangePicker = true
                    } else {
                        viewModel.onTimeRangeChange(range)
                    }
                },
            )

            // Quick search bar
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = { Text("Search logs...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )

            // Active filters indicator
            if (state.activeFilters.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.activeFilters.forEach { filter ->
                        FilterChip(
                            selected = true,
                            onClick = { viewModel.removeFilter(filter) },
                            label = { Text(filter, maxLines = 1) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove filter",
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    TextButton(onClick = viewModel::clearFilters) {
                        Text("Clear all")
                    }
                }
            }

            // Streaming indicator banner
            AnimatedVisibility(visible = state.isStreaming) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 0.3f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800, easing = EaseInOut),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "pulse_alpha",
                        )
                        Icon(
                            Icons.Filled.FiberManualRecord,
                            contentDescription = "Live streaming active",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .size(12.dp)
                                .alpha(alpha),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Live tail active - polling every 3s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::stopStreaming) {
                            Text("Stop")
                        }
                    }
                }
            }

            // Streaming error banner
            AnimatedVisibility(visible = state.streamingError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = "Streaming error",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.streamingError ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = viewModel::stopStreaming) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            // Log entries
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.error != null && state.logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = "Error loading logs",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = state.error!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = viewModel::refresh) {
                                Text("Retry")
                            }
                        }
                    }
                } else if (state.logs.isEmpty() && !state.isLoading) {
                    val emptyMessage = when {
                        state.searchQuery.isNotBlank() && state.activeFilters.isNotEmpty() ->
                            "No logs match \"${state.searchQuery}\" with the active filters"
                        state.searchQuery.isNotBlank() ->
                            "No logs match \"${state.searchQuery}\""
                        state.activeFilters.isNotEmpty() ->
                            "No logs match the active filters"
                        else ->
                            "No logs found for the selected time range"
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.SearchOff,
                                contentDescription = "No logs found",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = emptyMessage,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                        }
                    }
                } else {
                    val listState = rememberLazyListState()

                    // Auto-load more when scrolling near the bottom
                    if (state.hasMore && !state.isLoading) {
                        val shouldLoadMore by remember {
                            derivedStateOf {
                                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                lastVisible >= listState.layoutInfo.totalItemsCount - 5
                            }
                        }
                        LaunchedEffect(shouldLoadMore) {
                            if (shouldLoadMore) viewModel.loadMore()
                        }
                    }

                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(
                            state.logs,
                            key = { _, log -> stableLogKey(log) },
                        ) { _, logEntry ->
                            val key = remember(logEntry) { stableLogKey(logEntry) }
                            LogEntryCard(
                                logEntry = logEntry,
                                isExpanded = expandedLogKey == key,
                                onClick = {
                                    expandedLogKey = if (expandedLogKey == key) null else key
                                },
                                onCopied = {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = "Log entry copied to clipboard",
                                            duration = SnackbarDuration.Short,
                                        )
                                    }
                                },
                            )
                        }

                        if (state.hasMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Filter bottom sheet
    if (showFilterSheet) {
        FilterBottomSheet(
            columns = state.columns,
            onDismiss = { showFilterSheet = false },
            onApplyFilter = { column, operator, value ->
                viewModel.addFilter(column, operator, value)
                showFilterSheet = false
            },
        )
    }

    // SQL query bottom sheet
    if (showSqlSheet) {
        SqlQueryBottomSheet(
            streamName = streamName,
            currentSql = state.customSql,
            onDismiss = { showSqlSheet = false },
            onExecute = { sql ->
                viewModel.executeCustomSql(sql)
                showSqlSheet = false
            },
        )
    }

    // Share confirmation dialog (logs may contain sensitive data)
    if (showShareWarning) {
        AlertDialog(
            onDismissRequest = { showShareWarning = false },
            title = { Text("Share Logs") },
            text = {
                Text("Logs may contain sensitive information (PII, secrets, tokens). " +
                    "They will be exported as unencrypted JSON. Continue?")
            },
            confirmButton = {
                TextButton(onClick = {
                    showShareWarning = false
                    val logs = state.logs
                    if (logs.isNotEmpty()) {
                        scope.launch {
                            try {
                                val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val dir = java.io.File(context.cacheDir, "shared_logs")
                                    dir.mkdirs()
                                    val safeFileName = streamName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                    val file = java.io.File(dir, "logs_$safeFileName.json")
                                    file.bufferedWriter().use { writer ->
                                        val prettyJson = Json { prettyPrint = true }
                                        logs.forEachIndexed { index, log ->
                                            writer.write(prettyJson.encodeToString(JsonObject.serializer(), log))
                                            if (index < logs.lastIndex) writer.newLine()
                                        }
                                    }
                                    file
                                }
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "Logs: $streamName")
                                    type = "application/json"
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(sendIntent, "Share logs")
                                )
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(
                                    message = "Failed to share logs: ${e.message}",
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        }
                    }
                }) {
                    Text("Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareWarning = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Custom date range picker
    if (showDateRangePicker) {
        val dateRangePickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showDateRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val start = dateRangePickerState.selectedStartDateMillis
                        val end = dateRangePickerState.selectedEndDateMillis
                        if (start != null && end != null) {
                            // DateRangePicker returns midnight UTC for the selected date.
                            // Adjust to cover the full end day in the user's local timezone:
                            // Convert to local date, get end-of-day, convert back to UTC millis.
                            val localZone = java.time.ZoneId.systemDefault()
                            val endLocalDate = java.time.Instant.ofEpochMilli(end)
                                .atZone(localZone).toLocalDate()
                            val endOfDayUtc = endLocalDate.plusDays(1)
                                .atStartOfDay(localZone)
                                .toInstant().toEpochMilli() - 1
                            val startLocalDate = java.time.Instant.ofEpochMilli(start)
                                .atZone(localZone).toLocalDate()
                            val startUtc = startLocalDate
                                .atStartOfDay(localZone)
                                .toInstant().toEpochMilli()
                            viewModel.setCustomTimeRange(startUtc, endOfDayUtc)
                        }
                        showDateRangePicker = false
                    },
                    enabled = dateRangePickerState.selectedStartDateMillis != null &&
                        dateRangePickerState.selectedEndDateMillis != null,
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDateRangePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DateRangePicker(
                state = dateRangePickerState,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TimeRangeBar(
    selectedRange: TimeRange,
    onRangeSelected: (TimeRange) -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimeRange.entries.forEach { range ->
            FilterChip(
                selected = selectedRange == range,
                onClick = { onRangeSelected(range) },
                label = { Text(range.label) },
            )
        }
    }
}

@Composable
private fun StreamingToggleButton(
    isStreaming: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        if (isStreaming) {
            val infiniteTransition = rememberInfiniteTransition(label = "stream_pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "stream_btn_alpha",
            )
            Icon(
                Icons.Filled.Stop,
                contentDescription = "Stop streaming",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.alpha(alpha),
            )
        } else {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Start live tail",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun LogEntryCard(
    logEntry: JsonObject,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onCopied: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboardManager = remember {
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }
    val containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val cardColors = CardDefaults.cardColors(containerColor = containerColor)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = cardColors,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Timestamp row
            val rawTimestamp = remember(logEntry) {
                try {
                    logEntry["p_timestamp"]?.jsonPrimitive?.content
                        ?: logEntry["datetime"]?.jsonPrimitive?.content
                        ?: ""
                } catch (_: Exception) {
                    ""
                }
            }
            val displayTimestamp = remember(rawTimestamp) {
                if (rawTimestamp.isNotEmpty()) formatTimestamp(rawTimestamp) else ""
            }

            if (displayTimestamp.isNotEmpty()) {
                Text(
                    text = displayTimestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (!isExpanded) {
                // Compact view: show first meaningful fields
                val preview = remember(logEntry) {
                    logEntry.entries
                        .filter { !it.key.startsWith("p_") || it.key == "p_timestamp" }
                        .take(3)
                        .joinToString(" | ") { "${it.key}: ${formatJsonValue(it.value)}" }
                }
                Text(
                    text = preview.ifEmpty { logEntry.toString() },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )
            } else {
                // Expanded view: show all fields + copy button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = {
                            val prettyJson = Json { prettyPrint = true }
                            val text = prettyJson.encodeToString(JsonObject.serializer(), logEntry)
                            clipboardManager.setPrimaryClip(
                                android.content.ClipData.newPlainText("Log entry", text)
                            )
                            onCopied()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy log entry to clipboard",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                logEntry.entries.forEach { (key, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            text = "$key: ",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = formatJsonValue(value),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Produces a stable key for a log entry so that expanded state survives list mutations
 * (e.g., new logs prepended during streaming). Uses p_timestamp + p_metadata to form a
 * content-based identity, falling back to a hash of the entire entry.
 */
private fun stableLogKey(log: JsonObject): String {
    val ts = log["p_timestamp"]?.toString()
    val meta = log["p_metadata"]?.toString()
    return if (ts != null) "$ts|${meta.orEmpty()}" else log.toString()
}

private fun formatJsonValue(element: JsonElement): String {
    return when (element) {
        is JsonPrimitive -> element.content
        is JsonNull -> "null"
        else -> element.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBottomSheet(
    columns: List<String>,
    onDismiss: () -> Unit,
    onApplyFilter: (column: String, operator: String, value: String) -> Unit,
) {
    var selectedColumn by remember { mutableStateOf(columns.firstOrNull() ?: "") }
    var selectedOperator by remember { mutableStateOf("=") }
    var filterValue by remember { mutableStateOf("") }
    var columnDropdownExpanded by remember { mutableStateOf(false) }
    var operatorDropdownExpanded by remember { mutableStateOf(false) }

    val operators = listOf("=", "!=", "LIKE", "ILIKE", ">", "<", ">=", "<=", "IS NULL", "IS NOT NULL")

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Add Filter",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            // Column selector
            ExposedDropdownMenuBox(
                expanded = columnDropdownExpanded,
                onExpandedChange = { columnDropdownExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedColumn,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Column") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = columnDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = columnDropdownExpanded,
                    onDismissRequest = { columnDropdownExpanded = false },
                ) {
                    columns.forEach { column ->
                        DropdownMenuItem(
                            text = { Text(column) },
                            onClick = {
                                selectedColumn = column
                                columnDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            // Operator selector
            ExposedDropdownMenuBox(
                expanded = operatorDropdownExpanded,
                onExpandedChange = { operatorDropdownExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedOperator,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Operator") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = operatorDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = operatorDropdownExpanded,
                    onDismissRequest = { operatorDropdownExpanded = false },
                ) {
                    operators.forEach { op ->
                        DropdownMenuItem(
                            text = { Text(op) },
                            onClick = {
                                selectedOperator = op
                                operatorDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            // Value input (hidden for IS NULL / IS NOT NULL)
            if (selectedOperator !in listOf("IS NULL", "IS NOT NULL")) {
                val valueHint = when (selectedOperator) {
                    "LIKE", "ILIKE" -> "Pattern (% added automatically)"
                    ">", "<", ">=", "<=" -> "Numeric or date value"
                    else -> "Exact value"
                }
                val valueError = when {
                    filterValue.isNotEmpty() && selectedOperator in listOf(">", "<", ">=", "<=") &&
                        filterValue.toDoubleOrNull() == null &&
                        !filterValue.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*")) ->
                        "Expected a number or date"
                    else -> null
                }
                OutlinedTextField(
                    value = filterValue,
                    onValueChange = { filterValue = it },
                    label = { Text("Value") },
                    placeholder = { Text(valueHint) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = valueError != null,
                    supportingText = valueError?.let { { Text(it) } },
                )
            }

            val hasValueError = selectedOperator in listOf(">", "<", ">=", "<=") &&
                filterValue.isNotEmpty() &&
                filterValue.toDoubleOrNull() == null &&
                !filterValue.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*"))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (selectedColumn.isNotEmpty()) {
                            onApplyFilter(selectedColumn, selectedOperator, filterValue)
                        }
                    },
                    enabled = selectedColumn.isNotEmpty() &&
                        !hasValueError &&
                        (selectedOperator in listOf("IS NULL", "IS NOT NULL") || filterValue.isNotEmpty()),
                ) {
                    Text("Apply")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SqlQueryBottomSheet(
    streamName: String,
    currentSql: String,
    onDismiss: () -> Unit,
    onExecute: (String) -> Unit,
) {
    var sql by remember {
        mutableStateOf(
            currentSql.ifEmpty {
                "SELECT * FROM \"$streamName\" ORDER BY p_timestamp DESC LIMIT 100"
            }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "SQL Query",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "Use PostgreSQL-compatible SQL to query your logs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = sql,
                onValueChange = { sql = it },
                label = { Text("SQL") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                maxLines = 8,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onExecute(sql) },
                    enabled = sql.isNotBlank(),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Execute query")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Execute")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
