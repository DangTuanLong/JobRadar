package com.jobradar.app.domain.model

object AuthErrorCodes {
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    const val EMAIL_NOT_CONFIRMED = "EMAIL_NOT_CONFIRMED"
    const val USER_ALREADY_EXISTS = "USER_ALREADY_EXISTS"
    const val WEAK_PASSWORD = "WEAK_PASSWORD"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val NETWORK_ERROR = "NETWORK_ERROR"
    const val CONFIG_MISSING = "CONFIG_MISSING"
    const val UNKNOWN = "UNKNOWN"
}
