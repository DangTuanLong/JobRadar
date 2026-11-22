package com.jobradar.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AuthSessionState
import com.jobradar.app.domain.model.BackendHealth
import com.jobradar.app.domain.repository.AuthRepository
import com.jobradar.app.domain.repository.HealthRepository
import com.jobradar.app.ui.auth.AuthEvent
import com.jobradar.app.ui.auth.authErrorMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MILLIS = 5_000L

@HiltViewModel
class MainShellViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val healthRepository: HealthRepository,
) : ViewModel() {

    private val healthRefreshTrigger = MutableStateFlow(0)
    private val isSigningOut = MutableStateFlow(false)

    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events: Flow<AuthEvent> = _events.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val backendStatus: Flow<BackendStatus> = healthRefreshTrigger.flatMapLatest {
        flow {
            emit(BackendStatus.Checking)
            emit(mapHealthResult(healthRepository.checkHealth()))
        }
    }

    val uiState: StateFlow<MainShellUiState> = combine(
        authRepository.sessionState,
        backendStatus,
        isSigningOut,
    ) { session, status, signingOut ->
        val email = when (session) {
            is AuthSessionState.Authenticated -> session.email
            AuthSessionState.Loading,
            AuthSessionState.Unauthenticated,
            -> null
        }
        MainShellUiState(
            email = email,
            backendStatus = status,
            isSigningOut = signingOut,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = MainShellUiState(),
    )

    fun retryHealthCheck() {
        healthRefreshTrigger.update { it + 1 }
    }

    fun signOut() {
        if (isSigningOut.value) return
        isSigningOut.value = true

        viewModelScope.launch {
            when (val result = authRepository.signOut()) {
                is AppResult.Success -> {
                    isSigningOut.value = false
                }

                is AppResult.Error -> {
                    isSigningOut.value = false
                    _events.send(AuthEvent.ShowMessage(authErrorMessageRes(result.code)))
                }
            }
        }
    }

    private fun mapHealthResult(result: AppResult<BackendHealth>): BackendStatus = when (result) {
        is AppResult.Success -> BackendStatus.Online(environment = result.data.environment)
        is AppResult.Error -> BackendStatus.Offline(messageRes = authErrorMessageRes(result.code))
    }
}
