package com.jobradar.app.ui.auth

import androidx.annotation.StringRes
import com.jobradar.app.R
import com.jobradar.app.core.network.NetworkErrorCodes
import com.jobradar.app.domain.model.AuthErrorCodes

@StringRes
fun authErrorMessageRes(code: String): Int = when (code) {
    AuthErrorCodes.INVALID_CREDENTIALS -> R.string.auth_error_invalid_credentials
    AuthErrorCodes.EMAIL_NOT_CONFIRMED -> R.string.auth_error_email_not_confirmed
    AuthErrorCodes.USER_ALREADY_EXISTS -> R.string.auth_error_user_exists
    AuthErrorCodes.WEAK_PASSWORD -> R.string.auth_error_weak_password
    AuthErrorCodes.RATE_LIMITED -> R.string.auth_error_rate_limited
    AuthErrorCodes.CONFIG_MISSING -> R.string.auth_error_config_missing
    AuthErrorCodes.NETWORK_ERROR,
    NetworkErrorCodes.NETWORK_ERROR,
    NetworkErrorCodes.TIMEOUT,
    -> R.string.auth_error_network

    else -> R.string.auth_error_unknown
}
