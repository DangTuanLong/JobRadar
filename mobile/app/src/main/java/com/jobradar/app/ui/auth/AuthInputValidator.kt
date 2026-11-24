package com.jobradar.app.ui.auth

import androidx.annotation.StringRes
import com.jobradar.app.R
import java.text.Normalizer

object AuthInputValidator {
    const val MIN_PASSWORD_LENGTH = 6

    private val EMAIL_REGEX =
        Regex("^[A-Za-z0-9+_.'%-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")

    /**
     * Removes non-printable control characters (e.g. hardware-keyboard Ctrl+V / Backspace / DEL
     * artifacts on Android Emulator) and zero-width Unicode format characters (ZWSP, BOM, LRM/RLM).
     */
    fun stripInvisibleChars(rawEmail: String): String = buildString(rawEmail.length) {
        for (ch in rawEmail) {
            val category = ch.category
            if (category != CharCategory.CONTROL && category != CharCategory.FORMAT) {
                append(ch)
            }
        }
    }

    /**
     * Normalizes compatibility characters (e.g. fullwidth '@' or '.'), strips invisible
     * control/format characters, and trims leading/trailing Unicode whitespace.
     */
    fun sanitizeEmail(rawEmail: String): String {
        val normalized = Normalizer.normalize(rawEmail, Normalizer.Form.NFKC)
        return stripInvisibleChars(normalized).trim { it.isWhitespace() }
    }

    @StringRes
    fun validateEmail(rawEmail: String): Int? {
        val sanitized = sanitizeEmail(rawEmail)
        return when {
            sanitized.isEmpty() -> R.string.auth_error_email_empty
            !EMAIL_REGEX.matches(sanitized) -> R.string.auth_error_email_invalid
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
