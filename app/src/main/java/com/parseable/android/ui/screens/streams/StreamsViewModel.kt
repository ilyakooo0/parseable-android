package com.parseable.android.ui.screens.streams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.local.FavoriteStream
import com.parseable.android.data.local.FavoriteStreamDao
import com.parseable.android.data.formatBytes
import com.parseable.android.data.model.AboutInfo
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.model.LogStream
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

data class StreamsState(
    val streams: List<LogStream> = emptyList(),
    val streamStats: Map<String, StreamsViewModel.StreamStatsUi> = emptyMap(),
    val failedStats: Set<String> = emptySet(),
    val aboutInfo: AboutInfo? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val favoriteNames: Set<String> = emptySet(),
)

@HiltViewModel
class StreamsViewModel @Inject constructor(
    private val repository: ParseableRepository,
    private val settingsRepository: SettingsRepository,
    private val favoriteDao: FavoriteStreamDao,
) : ViewModel() {

    data class StreamStatsUi(
        val eventCount: Long? = null,
        val ingestionSize: String? = null,
        val storageSize: String? = null,
    )

    private val _state = MutableStateFlow(StreamsState())
    val state: StateFlow<StreamsState> = _state.asStateFlow()

    private val _snackbarEvent = Channel<String>(Channel.BUFFERED)
    val snackbarEvent = _snackbarEvent.receiveAsFlow()

    init {
        viewModelScope.launch {
            favoriteDao.getAllNames().collect { names ->
                _state.update { it.copy(favoriteNames = names.toSet()) }
            }
        }
        // Load once when the ViewModel is created. The screen no longer refreshes on every
        // RESUME, so returning via back-navigation reuses the already-loaded data.
        refresh()
    }

    fun toggleFavorite(streamName: String) {
        viewModelScope.launch {
            if (streamName in _state.value.favoriteNames) {
                favoriteDao.deleteByName(streamName)
                _snackbarEvent.send("Removed from favorites")
            } else {
                favoriteDao.insert(FavoriteStream(streamName = streamName))
                _snackbarEvent.send("Added to favorites")
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            try {
                // Fetch about info and streams in parallel
                val (aboutResult, streamsResult) = coroutineScope {
                    val aboutDeferred = async { repository.getAbout() }
                    val streamsDeferred = async { repository.listStreams(forceRefresh = true) }
                    aboutDeferred.await() to streamsDeferred.await()
                }

                if (aboutResult is ApiResult.Success) {
                    _state.update { it.copy(aboutInfo = aboutResult.data) }
                }

                when (streamsResult) {
                    is ApiResult.Success -> {
                        // Prune cached stats/failures for streams that no longer exist so
                        // deleted or recreated streams don't show stale numbers.
                        val names = streamsResult.data.mapTo(HashSet()) { it.name }
                        _state.update {
                            it.copy(
                                streams = streamsResult.data.sortedBy { s -> s.name.lowercase() },
                                streamStats = it.streamStats.filterKeys { name -> name in names },
                                failedStats = it.failedStats.filterTo(mutableSetOf()) { name -> name in names },
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
                                error = streamsResult.userMessage,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(isLoading = false) }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load streams")
                }
            }
        }
    }

    private val statsSemaphore = Semaphore(8)
    private var statsJob: Job? = null
    // Per-stream retry jobs, tracked so a new bulk refresh can cancel any in-flight
    // retry — otherwise a late retry result could overwrite freshly-loaded stats.
    private val retryJobs = mutableMapOf<String, Job>()

    private fun loadStreamStats(streams: List<LogStream>) {
        statsJob?.cancel()
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
        statsJob = viewModelScope.launch {
            streams.forEach { stream ->
                launch {
                    statsSemaphore.withPermit {
                        loadSingleStreamStats(stream.name)
                    }
                }
            }
        }
    }

    fun retryStats(streamName: String) {
        retryJobs[streamName]?.cancel()
        val job = viewModelScope.launch {
            _state.update { it.copy(failedStats = it.failedStats - streamName) }
            statsSemaphore.withPermit {
                loadSingleStreamStats(streamName)
            }
        }
        retryJobs[streamName] = job
    }

    private suspend fun loadSingleStreamStats(streamName: String) {
        when (val result = repository.getStreamStats(streamName)) {
            is ApiResult.Success -> {
                val stats = result.data
                val ui = StreamStatsUi(
                    eventCount = stats.ingestion?.count
                        ?: stats.ingestion?.lifetimeCount,
                    ingestionSize = formatBytes(stats.ingestion?.size)
                        ?: formatBytes(stats.ingestion?.lifetimeSize),
                    storageSize = formatBytes(stats.storage?.size)
                        ?: formatBytes(stats.storage?.lifetimeSize),
                )
                _state.update {
                    // Drop late results for streams a concurrent refresh has already pruned,
                    // so an in-flight retry can't resurrect stats for a deleted stream.
                    if (it.streams.none { s -> s.name == streamName }) it
                    else it.copy(
                        streamStats = it.streamStats + (streamName to ui),
                        failedStats = it.failedStats - streamName,
                    )
                }
            }
            is ApiResult.Error -> {
                _state.update {
                    if (it.streams.none { s -> s.name == streamName }) it
                    else it.copy(failedStats = it.failedStats + streamName)
                }
            }
        }
    }

    suspend fun logout() {
        settingsRepository.clearConfig()
        repository.clearCredentials()
    }
}
