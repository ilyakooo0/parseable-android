package com.parseable.android.ui.screens.logviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.model.*
import com.parseable.android.data.repository.ParseableRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

    fun load(streamName: String) {
        loadJob?.cancel()
        _state.update { it.copy(streamName = streamName, isLoading = true, error = null) }

        loadJob = viewModelScope.launch {
            try {
                val statsDeferred = async { repository.getStreamStats(streamName) }
                val schemaDeferred = async { repository.getStreamSchema(streamName) }
                val retentionDeferred = async { repository.getStreamRetention(streamName) }
                val infoDeferred = async { repository.getStreamInfo(streamName) }

                val statsResult = statsDeferred.await()
                val schemaResult = schemaDeferred.await()
                val retentionResult = retentionDeferred.await()
                val infoResult = infoDeferred.await()

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
        if (name.isEmpty()) return
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
