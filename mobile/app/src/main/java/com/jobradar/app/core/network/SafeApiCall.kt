package com.jobradar.app.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("SafeApiCall")
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404

/**
 * Executes a Retrofit call returning [Response] (covering 200, 202, etc.) and maps
 * standard API envelopes and transport failures to [AppResult].
 */
suspend fun <T> safeApiCall(
    json: Json,
    call: suspend () -> Response<ApiResponse<T>>,
): AppResult<T> = try {
    val response = call()
    if (response.isSuccessful) {
        response.body()?.toAppResult() ?: AppResult.Error(
            code = NetworkErrorCodes.EMPTY_RESPONSE,
            message = "Backend returned an empty response body.",
        )
    } else {
        logger.log(Level.WARNING, "HTTP {0} from backend", response.code())
        parseErrorResponse(
            json = json,
            statusCode = response.code(),
            statusMessage = response.message(),
            rawBody = safeReadErrorBody(response.errorBody()),
            cause = null,
        )
    }
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    logger.log(Level.WARNING, "HTTP ${e.code()} exception from backend", e)
    parseErrorResponse(
        json = json,
        statusCode = e.code(),
        statusMessage = e.message(),
        rawBody = safeReadErrorBody(e.response()?.errorBody()),
        cause = e,
    )
} catch (e: SocketTimeoutException) {
    logger.log(Level.WARNING, "Backend request timed out", e)
    AppResult.Error(
        code = NetworkErrorCodes.TIMEOUT,
        message = "Request timed out.",
        cause = e,
    )
} catch (e: IOException) {
    logger.log(Level.WARNING, "Network I/O error calling backend", e)
    AppResult.Error(
        code = NetworkErrorCodes.NETWORK_ERROR,
        message = "Network connection failed.",
        cause = e,
    )
} catch (e: SerializationException) {
    logger.log(Level.WARNING, "Failed to decode backend payload", e)
    AppResult.Error(
        code = NetworkErrorCodes.PARSE_ERROR,
        message = "Invalid response payload.",
        cause = e,
    )
} catch (e: Exception) {
    logger.log(Level.SEVERE, "Unexpected error calling backend", e)
    AppResult.Error(
        code = NetworkErrorCodes.UNKNOWN,
        message = e.message ?: "Unexpected error.",
        cause = e,
    )
}

private fun safeReadErrorBody(errorBody: ResponseBody?): String? {
    if (errorBody == null) return null
    return try {
        errorBody.string()
    } catch (ioException: IOException) {
        logger.log(Level.WARNING, "Unable to read HTTP error body", ioException)
        null
    }
}

private fun parseErrorResponse(
    json: Json,
    statusCode: Int,
    statusMessage: String?,
    rawBody: String?,
    cause: Throwable?,
): AppResult.Error {
    if (!rawBody.isNullOrBlank()) {
        val parsedError = try {
            json.decodeFromString<ApiResponse<JsonElement>>(rawBody).error
        } catch (serializationException: SerializationException) {
            logger.log(Level.WARNING, "Error body did not match ApiResponse envelope", serializationException)
            null
        }
        if (parsedError != null) {
            return AppResult.Error(
                code = parsedError.code,
                message = parsedError.message,
                cause = cause,
            )
        }
    }

    val fallbackCode = when (statusCode) {
        HTTP_UNAUTHORIZED -> NetworkErrorCodes.UNAUTHORIZED
        HTTP_NOT_FOUND -> NetworkErrorCodes.NOT_FOUND
        else -> NetworkErrorCodes.HTTP_ERROR
    }
    return AppResult.Error(
        code = fallbackCode,
        message = statusMessage?.takeIf { it.isNotBlank() } ?: "HTTP $statusCode",
        cause = cause,
    )
}
