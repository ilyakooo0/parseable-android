package com.parseable.android.ui.screens.logviewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSqlSheet by remember { mutableStateOf(false) }
    var expandedLogIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(streamName) {
        viewModel.initialize(streamName)
    }

    Scaffold(
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
                onRangeSelected = viewModel::onTimeRangeChange,
            )

            // Quick search bar
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = { Text("Search logs...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
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
                            contentDescription = null,
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
                                contentDescription = null,
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
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No logs found for the selected time range",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            state.logs,
                            key = { it.hashCode() },
                        ) { logEntry ->
                            val index = state.logs.indexOf(logEntry)
                            LogEntryCard(
                                logEntry = logEntry,
                                isExpanded = expandedLogIndex == index,
                                onClick = {
                                    expandedLogIndex = if (expandedLogIndex == index) -1 else index
                                },
                                columns = state.columns,
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
                                    OutlinedButton(onClick = viewModel::loadMore) {
                                        Text("Load more")
                                    }
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
    columns: List<String>,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Timestamp row
            val timestamp = logEntry["p_timestamp"]?.jsonPrimitive?.content
                ?: logEntry["datetime"]?.jsonPrimitive?.content
                ?: ""

            if (timestamp.isNotEmpty()) {
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (!isExpanded) {
                // Compact view: show first meaningful fields
                val preview = logEntry.entries
                    .filter { !it.key.startsWith("p_") || it.key == "p_timestamp" }
                    .take(3)
                    .joinToString(" | ") { "${it.key}: ${formatJsonValue(it.value)}" }
                Text(
                    text = preview.ifEmpty { logEntry.toString() },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )
            } else {
                // Expanded view: show all fields
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
                        .menuAnchor(),
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
                        .menuAnchor(),
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
                OutlinedTextField(
                    value = filterValue,
                    onValueChange = { filterValue = it },
                    label = { Text("Value") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

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
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Execute")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
