package com.jobradar.app.ui.auth

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

@Immutable
data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isPasswordVisible: Boolean = false,
    @param:StringRes val emailError: Int? = null,
    @param:StringRes val passwordError: Int? = null,
    @param:StringRes val confirmPasswordError: Int? = null,
    val isLoading: Boolean = false,
    @param:StringRes val generalError: Int? = null,
)

sealed interface AuthEvent {
    data class ShowMessage(@param:StringRes val messageRes: Int) : AuthEvent

    data class RegistrationPendingConfirmation(@param:StringRes val messageRes: Int) : AuthEvent
}
