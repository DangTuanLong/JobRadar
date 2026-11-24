package com.jobradar.app.ui.matches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.MatchSortOption
import com.jobradar.app.domain.repository.MatchRepository
import com.jobradar.app.domain.state.ActiveAnalysisStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MatchesViewModel @Inject constructor(
    private val matchRepository: MatchRepository,
    activeAnalysisStore: ActiveAnalysisStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MatchesUiState())
    val uiState: StateFlow<MatchesUiState> = _uiState.asStateFlow()

    val latestCompletedJobId: StateFlow<String?> = activeAnalysisStore.latestCompletedJobId

    private var loadedJobId: String? = null
    private var loadJob: Job? = null

    fun syncWithCompletedJob(jobId: String?) {
        val cleanId = jobId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanId == null) {
            loadJob?.cancel()
            loadedJobId = null
            _uiState.value = MatchesUiState(contentState = MatchesContentState.NoJob)
            return
        }

        if (cleanId != loadedJobId) {
            loadedJobId = cleanId
            _uiState.update { state ->
                state.copy(
                    activeJobId = cleanId,
                    searchQuery = "",
                    selectedLevel = null,
                    sortOption = MatchSortOption.OVERALL_SCORE,
                )
            }
            loadMatches(jobId = cleanId, forceRefresh = false, silentRefresh = false)
        }
    }

    fun refreshMatches() {
        val jobId = _uiState.value.activeJobId ?: return
        val silent = _uiState.value.contentState is MatchesContentState.Loaded
        loadMatches(jobId = jobId, forceRefresh = true, silentRefresh = silent)
    }

    fun retryLoad() {
        val jobId = _uiState.value.activeJobId ?: return
        loadMatches(jobId = jobId, forceRefresh = true, silentRefresh = false)
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onLevelSelected(level: String?) {
        _uiState.update { state ->
            val nextLevel = if (state.selectedLevel.equals(level, ignoreCase = true)) null else level
            state.copy(selectedLevel = nextLevel)
        }
    }

    fun onSortOptionChange(option: MatchSortOption) {
        _uiState.update { it.copy(sortOption = option) }
    }

    fun clearFilters() {
        _uiState.update { it.copy(searchQuery = "", selectedLevel = null) }
    }

    private fun loadMatches(
        jobId: String,
        forceRefresh: Boolean,
        silentRefresh: Boolean,
    ) {
        loadJob?.cancel()
        _uiState.update { state ->
            if (silentRefresh && state.contentState is MatchesContentState.Loaded) {
                state.copy(isRefreshing = true)
            } else {
                state.copy(contentState = MatchesContentState.Loading, isRefreshing = false)
            }
        }

        loadJob = viewModelScope.launch {
            when (val result = matchRepository.getMatches(jobId = jobId, forceRefresh = forceRefresh)) {
                is AppResult.Success -> {
                    val items = result.data
                    _uiState.update { state ->
                        state.copy(
                            contentState = if (items.isEmpty()) {
                                MatchesContentState.Empty
                            } else {
                                MatchesContentState.Loaded(items)
                            },
                            isRefreshing = false,
                        )
                    }
                }

                is AppResult.Error -> {
                    _uiState.update { state ->
                        state.copy(
                            contentState = MatchesContentState.Error(mapMatchErrorMessageRes(result.code)),
                            isRefreshing = false,
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
    }
}
