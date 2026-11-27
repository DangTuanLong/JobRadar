package com.jobradar.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.repository.AuthRepository
import com.jobradar.app.ui.auth.AuthEvent
import com.jobradar.app.ui.auth.authErrorMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainShellViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainShellUiState())
    val uiState: StateFlow<MainShellUiState> = _uiState.asStateFlow()

    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events: Flow<AuthEvent> = _events.receiveAsFlow()

    fun signOut() {
        if (_uiState.value.isSigningOut) return
        _uiState.update { it.copy(isSigningOut = true) }

        viewModelScope.launch {
            when (val result = authRepository.signOut()) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isSigningOut = false) }
                }

                is AppResult.Error -> {
                    _uiState.update { it.copy(isSigningOut = false) }
                    _events.send(AuthEvent.ShowMessage(authErrorMessageRes(result.code)))
                }
            }
        }
    }
}
