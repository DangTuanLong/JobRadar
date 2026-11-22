package com.jobradar.app.ui.auth

import androidx.annotation.StringRes
import com.jobradar.app.R

object AuthInputValidator {
    const val MIN_PASSWORD_LENGTH = 6

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    @StringRes
    fun validateEmail(rawEmail: String): Int? {
        val trimmed = rawEmail.trim()
        return when {
            trimmed.isEmpty() -> R.string.auth_error_email_empty
            !EMAIL_REGEX.matches(trimmed) -> R.string.auth_error_email_invalid
            else -> null
        }
    }

    @StringRes
    fun validatePassword(password: String): Int? =
        if (password.length < MIN_PASSWORD_LENGTH) {
            R.string.auth_error_password_short
        } else {
            null
        }

    @StringRes
    fun validateConfirmPassword(password: String, confirmPassword: String): Int? =
        if (confirmPassword != password) {
            R.string.auth_error_password_mismatch
        } else {
            null
        }
}
