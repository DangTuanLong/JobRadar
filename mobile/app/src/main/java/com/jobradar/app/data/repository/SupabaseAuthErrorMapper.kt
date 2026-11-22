package com.jobradar.app.data.repository

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AuthErrorCodes
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException

private const val SUPABASE_INVALID_CREDENTIALS = "invalid_credentials"
private const val SUPABASE_EMAIL_NOT_CONFIRMED = "email_not_confirmed"
private const val SUPABASE_USER_ALREADY_EXISTS = "user_already_exists"
private const val SUPABASE_EMAIL_EXISTS = "email_exists"
private const val SUPABASE_WEAK_PASSWORD = "weak_password"
private const val SUPABASE_OVER_REQUEST_RATE_LIMIT = "over_request_rate_limit"
private const val SUPABASE_OVER_EMAIL_RATE_LIMIT = "over_email_send_rate_limit"
private const val HTTP_TOO_MANY_REQUESTS = 429

/**
 * Maps Supabase Auth and Ktor/I/O exceptions to stable domain [AuthErrorCodes].
 */
internal fun Throwable.toAuthError(): AppResult.Error = when (this) {
    is RestException -> mapRestException(this)
    is HttpRequestException,
    is HttpRequestTimeoutException,
    is IOException,
    -> AppResult.Error(
        code = AuthErrorCodes.NETWORK_ERROR,
        message = message ?: "Network connection failed.",
        cause = this,
    )

    else -> AppResult.Error(
        code = AuthErrorCodes.UNKNOWN,
        message = message ?: "Unexpected authentication error.",
        cause = this,
    )
}

private fun mapRestException(exception: RestException): AppResult.Error {
    val mappedCode = when {
        exception.statusCode == HTTP_TOO_MANY_REQUESTS -> AuthErrorCodes.RATE_LIMITED
        exception.error == SUPABASE_INVALID_CREDENTIALS -> AuthErrorCodes.INVALID_CREDENTIALS
        exception.error == SUPABASE_EMAIL_NOT_CONFIRMED -> AuthErrorCodes.EMAIL_NOT_CONFIRMED
        exception.error == SUPABASE_USER_ALREADY_EXISTS ||
            exception.error == SUPABASE_EMAIL_EXISTS -> AuthErrorCodes.USER_ALREADY_EXISTS

        exception.error == SUPABASE_WEAK_PASSWORD -> AuthErrorCodes.WEAK_PASSWORD
        exception.error == SUPABASE_OVER_REQUEST_RATE_LIMIT ||
            exception.error == SUPABASE_OVER_EMAIL_RATE_LIMIT -> AuthErrorCodes.RATE_LIMITED

        else -> AuthErrorCodes.UNKNOWN
    }
    return AppResult.Error(
        code = mappedCode,
        message = exception.description?.takeIf { it.isNotBlank() } ?: exception.error,
        cause = exception,
    )
}
