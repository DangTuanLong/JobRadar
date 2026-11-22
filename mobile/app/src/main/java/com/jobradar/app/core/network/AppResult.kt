package com.jobradar.app.core.network

/**
 * Unified domain result wrapper for repository and network operations.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>

    data class Error(
        val code: String,
        val message: String,
        val cause: Throwable? = null,
    ) : AppResult<Nothing>
}
