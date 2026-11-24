package com.jobradar.app.ui.matches

import androidx.annotation.StringRes
import com.jobradar.app.R
import com.jobradar.app.core.network.NetworkErrorCodes
import com.jobradar.app.data.repository.MATCH_NOT_FOUND_CODE
import java.util.Locale

@StringRes
fun mapMatchErrorMessageRes(
    code: String?,
    @StringRes fallbackRes: Int = R.string.matches_error_load,
): Int = when (code) {
    MATCH_NOT_FOUND_CODE -> R.string.matches_error_not_found
    "JOB_NOT_FOUND",
    NetworkErrorCodes.NOT_FOUND,
    -> R.string.upload_error_job_not_found

    "AUTH_MISSING_TOKEN",
    "AUTH_INVALID_TOKEN",
    "AUTH_VERIFICATION_FAILED",
    "AUTH_INVALID_PAYLOAD",
    NetworkErrorCodes.UNAUTHORIZED,
    -> R.string.upload_error_session_expired

    NetworkErrorCodes.NETWORK_ERROR,
    NetworkErrorCodes.TIMEOUT,
    -> R.string.auth_error_network

    else -> fallbackRes
}

fun formatJobLevelLabel(rawLevel: String?): String? {
    val trimmed = rawLevel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
    }
}
