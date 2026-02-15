package com.parseable.android.ui.screens.streams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.model.AboutInfo
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.model.LogStream
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StreamsState(
    val streams: List<LogStream> = emptyList(),
    val streamStats: Map<String, StreamsViewModel.StreamStatsUi> = emptyMap(),
    val aboutInfo: AboutInfo? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class StreamsViewModel @Inject constructor(
    private val repository: ParseableRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    data class StreamStatsUi(
        val eventCount: Long? = null,
        val ingestionSize: String? = null,
        val storageSize: String? = null,
    )

    private val _state = MutableStateFlow(StreamsState())
    val state: StateFlow<StreamsState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            // Fetch about info and streams in parallel
            val aboutDeferred = async { repository.getAbout() }
            val streamsDeferred = async { repository.listStreams() }

            val aboutResult = aboutDeferred.await()
            val streamsResult = streamsDeferred.await()

            if (aboutResult is ApiResult.Success) {
                _state.update { it.copy(aboutInfo = aboutResult.data) }
            }

            when (streamsResult) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            streams = streamsResult.data,
                            isLoading = false,
                        )
                    }
                    // Load stats for each stream in parallel
                    loadStreamStats(streamsResult.data)
                }
                is ApiResult.Error -> {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = streamsResult.message,
                        )
                    }
                }
            }
        }
    }

    private fun loadStreamStats(streams: List<LogStream>) {
        viewModelScope.launch {
            val statsMap = mutableMapOf<String, StreamStatsUi>()
            streams.map { stream ->
                async {
                    val result = repository.getStreamStats(stream.name)
                    if (result is ApiResult.Success) {
                        val stats = result.data
                        stream.name to StreamStatsUi(
                            eventCount = stats.ingestion?.count
                                ?: stats.ingestion?.lifetimeCount,
                            ingestionSize = stats.ingestion?.size
                                ?: stats.ingestion?.lifetimeSize,
                            storageSize = stats.storage?.size
                                ?: stats.storage?.lifetimeSize,
                        )
                    } else {
                        null
                    }
                }
            }.awaitAll().filterNotNull().forEach { (name, stats) ->
                statsMap[name] = stats
            }
            _state.update { it.copy(streamStats = statsMap) }
        }
    }

    fun logout() {
        viewModelScope.launch {
            settingsRepository.clearConfig()
        }
    }
}
