package com.parseable.android.ui.screens.logviewer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parseable.android.data.formatBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val prettyJson = Json { prettyPrint = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamInfoScreen(
    streamName: String,
    onBack: () -> Unit,
    onStreamDeleted: () -> Unit,
    viewModel: StreamInfoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Load once per stream. Reloading on every RESUME would refetch all four endpoints on
    // each return and could re-query a stream that was just deleted (spurious error card).
    LaunchedEffect(streamName) {
        viewModel.load(streamName)
    }

    LaunchedEffect(state.deleteSuccess) {
        if (state.deleteSuccess) {
            viewModel.consumeDeleteSuccess()
            onStreamDeleted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stream: $streamName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDeleteConfirmation = true },
                        enabled = !state.isDeleting && !state.isLoading,
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete stream",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        // Has any section loaded yet? Drives whether a load shows the centered first-load
        // spinner or keeps existing content visible (so pull-to-refresh stays usable on retry).
        val hasContent = state.stats != null || state.schema.isNotEmpty() ||
            state.rawInfo != null || state.retention.isNotEmpty() || state.schemaFailed
        PullToRefreshBox(
            // Only show the pull indicator when refreshing over existing content; the very
            // first load uses the centered spinner below, so don't show both at once.
            isRefreshing = state.isLoading && hasContent,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.isLoading && !hasContent) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Stats section
                    state.stats?.let { stats ->
                        InfoSection(title = "Statistics") {
                            // Show the current-window count when available. Fall back to "N/A"
                            // only when there's no lifetime count either — otherwise the lifetime
                            // figure is shown in its own row below, so don't duplicate it here.
                            when {
                                stats.ingestion?.count != null -> InfoRow("Event Count", stats.ingestion.count.toString())
                                stats.ingestion?.lifetimeCount == null -> InfoRow("Event Count", "N/A")
                            }
                            InfoRow("Ingestion Size", formatBytes(stats.ingestion?.size) ?: formatBytes(stats.ingestion?.lifetimeSize) ?: "N/A")
                            InfoRow("Storage Size", formatBytes(stats.storage?.size) ?: formatBytes(stats.storage?.lifetimeSize) ?: "N/A")
                            if (stats.ingestion?.lifetimeCount != null) {
                                InfoRow("Lifetime Events", stats.ingestion.lifetimeCount.toString())
                            }
                            if (stats.ingestion?.deletedCount != null) {
                                InfoRow("Deleted Events", stats.ingestion.deletedCount.toString())
                            }
                        }
                    }

                    // Schema section
                    if (state.schema.isNotEmpty()) {
                        InfoSection(title = "Schema (${state.schema.size} fields)") {
                            state.schema.forEach { field ->
                                InfoRow(
                                    field.name,
                                    field.dataType?.toString()?.trim('"') ?: "Unknown",
                                )
                            }
                        }
                    } else if (state.schemaFailed) {
                        InfoSection(title = "Schema") {
                            Text(
                                text = "Schema unavailable",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Retention section
                    if (state.retention.isNotEmpty()) {
                        InfoSection(title = "Retention") {
                            state.retention.forEach { r ->
                                InfoRow("Duration", r.duration ?: "N/A")
                                InfoRow("Action", r.action ?: "N/A")
                                if (r.description != null) {
                                    InfoRow("Description", r.description)
                                }
                            }
                        }
                    }

                    // Raw info section
                    state.rawInfo?.let { rawInfo ->
                        val prettyInfo = remember(rawInfo) {
                            prettyJson.encodeToString(JsonObject.serializer(), rawInfo)
                        }
                        InfoSection(title = "Stream Info") {
                            Text(
                                text = prettyInfo,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }

                    state.error?.let { errorText ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Text(
                                text = errorText,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Delete Stream") },
            text = {
                Text(
                    "Are you sure you want to delete \"$streamName\"? " +
                        "This will permanently remove all log data and cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteStream()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun InfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
        )
    }
}
