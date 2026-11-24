package com.jobradar.app.ui.matches.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.repository.MatchRepository
import com.jobradar.app.ui.matches.mapMatchErrorMessageRes
import com.jobradar.app.ui.navigation.MatchDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val ARG_JOB_ID = "jobId"
private const val ARG_MATCH_ID = "matchId"

@HiltViewModel
class MatchDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val matchRepository: MatchRepository,
) : ViewModel() {

    private val route: MatchDetailRoute = extractRoute(savedStateHandle)

    private val _uiState = MutableStateFlow<MatchDetailUiState>(MatchDetailUiState.Loading)
    val uiState: StateFlow<MatchDetailUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun loadDetail() {
        loadJob?.cancel()
        _uiState.value = MatchDetailUiState.Loading

        loadJob = viewModelScope.launch {
            when (
                val result = matchRepository.getMatchDetail(
                    jobId = route.jobId,
                    matchId = route.matchId,
                )
            ) {
                is AppResult.Success -> {
                    _uiState.value = MatchDetailUiState.Loaded(result.data)
                }

                is AppResult.Error -> {
                    _uiState.value = MatchDetailUiState.Error(mapMatchErrorMessageRes(result.code))
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
    }

    private fun extractRoute(savedStateHandle: SavedStateHandle): MatchDetailRoute =
        try {
            savedStateHandle.toRoute()
        } catch (e: RuntimeException) {
            // SavedStateHandle.toRoute() invokes Bundle.putCharSequence(), which is unmocked on pure JVM tests.
            val jobId = savedStateHandle.get<String>(ARG_JOB_ID)
            val matchId = savedStateHandle.get<String>(ARG_MATCH_ID)
            if (jobId != null && matchId != null) {
                MatchDetailRoute(jobId = jobId, matchId = matchId)
            } else {
                throw e
            }
        }
}
