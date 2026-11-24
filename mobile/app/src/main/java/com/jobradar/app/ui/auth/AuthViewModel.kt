package com.jobradar.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobradar.app.R
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.SignUpOutcome
import com.jobradar.app.domain.repository.AuthRepository
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
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events: Flow<AuthEvent> = _events.receiveAsFlow()

    fun onEmailChange(value: String) {
        val cleaned = AuthInputValidator.stripInvisibleChars(value)
        _uiState.update { state ->
            state.copy(
                email = cleaned,
                emailError = null,
                generalError = null,
            )
        }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { state ->
            state.copy(
                password = value,
                passwordError = null,
                generalError = null,
            )
        }
    }

    fun onConfirmPasswordChange(value: String) {
        _uiState.update { state ->
            state.copy(
                confirmPassword = value,
                confirmPasswordError = null,
                generalError = null,
            )
        }
    }

    fun onTogglePasswordVisibility() {
        _uiState.update { state ->
            state.copy(isPasswordVisible = !state.isPasswordVisible)
        }
    }

    fun login() {
        val current = _uiState.value
        if (current.isLoading) return

        val sanitizedEmail = AuthInputValidator.sanitizeEmail(current.email)
        val emailError = AuthInputValidator.validateEmail(sanitizedEmail)
        val passwordError = AuthInputValidator.validatePassword(current.password)
        if (emailError != null || passwordError != null) {
            _uiState.update {
                it.copy(
                    email = sanitizedEmail,
                    emailError = emailError,
                    passwordError = passwordError,
                    generalError = null,
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                email = sanitizedEmail,
                isLoading = true,
                emailError = null,
                passwordError = null,
                generalError = null,
            )
        }

        viewModelScope.launch {
            when (val result = authRepository.signIn(sanitizedEmail, current.password)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }

                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            generalError = authErrorMessageRes(result.code),
                        )
                    }
                }
            }
        }
    }

    fun register() {
        val current = _uiState.value
        if (current.isLoading) return

        val sanitizedEmail = AuthInputValidator.sanitizeEmail(current.email)
        val emailError = AuthInputValidator.validateEmail(sanitizedEmail)
        val passwordError = AuthInputValidator.validatePassword(current.password)
        val confirmError = AuthInputValidator.validateConfirmPassword(
            password = current.password,
            confirmPassword = current.confirmPassword,
        )
        if (emailError != null || passwordError != null || confirmError != null) {
            _uiState.update {
                it.copy(
                    email = sanitizedEmail,
                    emailError = emailError,
                    passwordError = passwordError,
                    confirmPasswordError = confirmError,
                    generalError = null,
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                email = sanitizedEmail,
                isLoading = true,
                emailError = null,
                passwordError = null,
                confirmPasswordError = null,
                generalError = null,
            )
        }

        viewModelScope.launch {
            when (val result = authRepository.signUp(sanitizedEmail, current.password)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                    when (result.data) {
                        SignUpOutcome.SIGNED_IN -> Unit
                        SignUpOutcome.CONFIRMATION_REQUIRED -> {
                            _events.send(
                                AuthEvent.RegistrationPendingConfirmation(
                                    R.string.auth_register_confirm_email,
                                ),
                            )
                        }
                    }
                }

                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            generalError = authErrorMessageRes(result.code),
                        )
                    }
                }
            }
        }
    }
}
