package com.jobradar.app.domain.model

/**
 * Represents the user authentication session lifecycle observed by the UI.
 */
sealed interface AuthSessionState {
    data object Loading : AuthSessionState

    data class Authenticated(
        val userId: String,
        val email: String?,
    ) : AuthSessionState

    data object Unauthenticated : AuthSessionState
}
