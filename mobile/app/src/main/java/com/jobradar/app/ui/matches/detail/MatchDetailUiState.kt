package com.jobradar.app.ui.matches.detail

import androidx.annotation.StringRes
import com.jobradar.app.domain.model.MatchResult

sealed interface MatchDetailUiState {
    data object Loading : MatchDetailUiState

    data class Loaded(val match: MatchResult) : MatchDetailUiState

    data class Error(@param:StringRes val messageRes: Int) : MatchDetailUiState
}
