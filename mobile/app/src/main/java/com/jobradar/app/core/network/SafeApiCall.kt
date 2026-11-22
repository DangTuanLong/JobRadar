package com.jobradar.app.core.network

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

private const val TAG = "SafeApiCall"

/**
 * Executes a Retrofit call and maps standard API envelopes and transport failures to [AppResult].
 */
suspend fun <T> safeApiCall(
    json: Json,
    call: suspend () -> ApiResponse<T>,
): AppResult<T> = try {
    call().toAppResult()
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    Log.w(TAG, "HTTP ${e.code()} from backend", e)
    parseHttpError(json = json, exception = e)
} catch (e: SocketTimeoutException) {
    Log.w(TAG, "Backend request timed out", e)
    AppResult.Error(
        code = NetworkErrorCodes.TIMEOUT,
        message = "Request timed out.",
        cause = e,
    )
} catch (e: IOException) {
    Log.w(TAG, "Network I/O error calling backend", e)
    AppResult.Error(
        code = NetworkErrorCodes.NETWORK_ERROR,
        message = "Network connection failed.",
        cause = e,
    )
} catch (e: SerializationException) {
    Log.w(TAG, "Failed to decode backend payload", e)
    AppResult.Error(
        code = NetworkErrorCodes.PARSE_ERROR,
        message = "Invalid response payload.",
        cause = e,
    )
} catch (e: Exception) {
    Log.e(TAG, "Unexpected error calling backend", e)
    AppResult.Error(
        code = NetworkErrorCodes.UNKNOWN,
        message = e.message ?: "Unexpected error.",
        cause = e,
    )
}

private fun parseHttpError(
    json: Json,
    exception: HttpException,
): AppResult.Error {
    val rawBody = try {
        exception.response()?.errorBody()?.string()
    } catch (ioException: IOException) {
        Log.w(TAG, "Unable to read HTTP error body", ioException)
        null
    }

    if (!rawBody.isNullOrBlank()) {
        val parsedError = try {
            json.decodeFromString<ApiResponse<JsonElement>>(rawBody).error
        } catch (serializationException: SerializationException) {
            Log.w(TAG, "Error body did not match standard ApiResponse envelope", serializationException)
            null
        }
        if (parsedError != null) {
            return AppResult.Error(
                code = parsedError.code,
                message = parsedError.message,
                cause = exception,
            )
        }
    }

    return AppResult.Error(
        code = NetworkErrorCodes.HTTP_ERROR,
        message = exception.message()?.takeIf { it.isNotBlank() } ?: "HTTP ${exception.code()}",
        cause = exception,
    )
}
