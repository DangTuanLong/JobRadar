package com.jobradar.app.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Standard JSON envelope returned by all JobRadar REST endpoints.
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
)

fun <T> ApiResponse<T>.toAppResult(): AppResult<T> = when {
    success && data != null -> AppResult.Success(data)
    error != null -> AppResult.Error(code = error.code, message = error.message)
    else -> AppResult.Error(
        code = NetworkErrorCodes.EMPTY_RESPONSE,
        message = "Backend returned an empty response payload.",
    )
}
