package com.parseable.android.ui.screens.logviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.model.*
import com.parseable.android.data.repository.ParseableRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

data class StreamInfoState(
    val streamName: String = "",
    val stats: StreamStats? = null,
    val schema: List<SchemaField> = emptyList(),
    val schemaFailed: Boolean = false,
    val retention: List<RetentionConfig> = emptyList(),
    val rawInfo: JsonObject? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isDeleting: Boolean = false,
    val deleteSuccess: Boolean = false,
)

@HiltViewModel
class StreamInfoViewModel @Inject constructor(
    private val repository: ParseableRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StreamInfoState())
    val state: StateFlow<StreamInfoState> = _state.asStateFlow()
    private var loadJob: Job? = null

    /** Holds the four parallel load results so coroutineScope can return them with their types intact. */
    private data class LoadResults(
        val stats: ApiResult<StreamStats>,
        val schema: ApiResult<StreamSchema>,
        val retention: ApiResult<List<RetentionConfig>>,
        val info: ApiResult<JsonObject>,
    )

    fun load(streamName: String) {
        // Already showing (or loading) this stream — don't cancel and refetch all four
        // endpoints on recomposition / config change. Matches LogViewerViewModel.initialize's
        // guard; the screen loads once per stream (see StreamInfoScreen's LaunchedEffect).
        if (_state.value.streamName == streamName) return
        fetch(streamName)
    }

    /**
     * Re-fetch the currently-shown stream, bypassing load()'s identity guard. Wired to
     * pull-to-refresh so the user can retry after a transient endpoint failure (e.g. a failed
     * schema fetch or a delete that errored) without leaving and re-entering the screen.
     */
    fun refresh() {
        val name = _state.value.streamName
        if (name.isEmpty()) return
        fetch(name)
    }

    private fun fetch(streamName: String) {
        loadJob?.cancel()
        _state.update { it.copy(streamName = streamName, isLoading = true, error = null) }

        loadJob = viewModelScope.launch {
            try {
                // Wrap the parallel fetches in coroutineScope so the four async children
                // form their own structured-concurrency scope (matching StreamsViewModel /
                // SettingsViewModel). The repository returns ApiResult.Error rather than
                // throwing, but if any call did throw unexpectedly this keeps the failure
                // contained to this load instead of it propagating oddly through the launch.
                val results = coroutineScope {
                    val statsDeferred = async { repository.getStreamStats(streamName) }
                    val schemaDeferred = async { repository.getStreamSchema(streamName) }
                    val retentionDeferred = async { repository.getStreamRetention(streamName) }
                    val infoDeferred = async { repository.getStreamInfo(streamName) }

                    LoadResults(
                        stats = statsDeferred.await(),
                        schema = schemaDeferred.await(),
                        retention = retentionDeferred.await(),
                        info = infoDeferred.await(),
                    )
                }
                val statsResult = results.stats
                val schemaResult = results.schema
                val retentionResult = results.retention
                val infoResult = results.info

                _state.update {
                    it.copy(
                        stats = (statsResult as? ApiResult.Success)?.data,
                        schema = (schemaResult as? ApiResult.Success)?.data?.fields ?: emptyList(),
                        schemaFailed = schemaResult is ApiResult.Error,
                        retention = (retentionResult as? ApiResult.Success)?.data ?: emptyList(),
                        rawInfo = (infoResult as? ApiResult.Success)?.data,
                        isLoading = false,
                        error = listOfNotNull(
                            (statsResult as? ApiResult.Error)?.userMessage,
                            (schemaResult as? ApiResult.Error)?.userMessage,
                            (retentionResult as? ApiResult.Error)?.userMessage,
                            (infoResult as? ApiResult.Error)?.userMessage,
                        ).distinct().joinToString("\n").ifEmpty { null },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load stream info")
                }
            }
        }
    }

    fun consumeDeleteSuccess() {
        _state.update { it.copy(deleteSuccess = false) }
    }

    fun deleteStream() {
        val name = _state.value.streamName
        // Guard against a no-op or a second tap firing a duplicate DELETE while one is in flight.
        if (name.isEmpty() || _state.value.isDeleting) return
        // Cancel any in-flight load so its terminal update can't re-populate stats/schema for the
        // stream we're about to delete after the DELETE completes.
        loadJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(isDeleting = true, error = null) }
            when (val result = repository.deleteStream(name)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isDeleting = false, deleteSuccess = true) }
                }
                is ApiResult.Error -> {
                    _state.update {
                        it.copy(isDeleting = false, error = "Delete failed: ${result.userMessage}")
                    }
                }
            }
        }
    }
}
