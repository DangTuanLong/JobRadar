package com.jobradar.app.ui.upload

import androidx.annotation.StringRes
import com.jobradar.app.R
import com.jobradar.app.core.network.NetworkErrorCodes
import com.jobradar.app.domain.validator.DocumentValidationError

@StringRes
fun mapDocumentValidationError(error: DocumentValidationError): Int = when (error) {
    DocumentValidationError.EMPTY_FILE -> R.string.upload_error_file_empty
    DocumentValidationError.FILE_TOO_LARGE -> R.string.upload_error_file_too_large
    DocumentValidationError.UNSUPPORTED_CV_FORMAT -> R.string.upload_error_cv_unsupported_format
    DocumentValidationError.UNSUPPORTED_LINKEDIN_FORMAT -> R.string.upload_error_linkedin_unsupported_format
}

@StringRes
fun mapUploadErrorMessageRes(code: String?): Int = when (code) {
    "CV_FILE_EMPTY" -> R.string.upload_error_file_empty
    "CV_FILE_TOO_LARGE" -> R.string.upload_error_file_too_large
    "CV_UNSUPPORTED_FORMAT" -> R.string.upload_error_cv_unsupported_format
    "CV_CORRUPT_FILE" -> R.string.upload_error_cv_corrupt
    "CV_TOO_MANY_PAGES" -> R.string.upload_error_cv_too_many_pages
    "CV_PARSE_FAILED" -> R.string.upload_error_cv_parse_failed
    "JOB_NOT_FOUND",
    NetworkErrorCodes.NOT_FOUND,
    -> R.string.upload_error_job_not_found

    "AI_RATE_LIMITED" -> R.string.upload_error_ai_rate_limited
    "AI_SERVICE_UNAVAILABLE",
    "AI_SERVICE_ERROR",
    "AI_CONFIG_ERROR",
    -> R.string.upload_error_ai_service

    "AUTH_MISSING_TOKEN",
    "AUTH_INVALID_TOKEN",
    "AUTH_VERIFICATION_FAILED",
    "AUTH_INVALID_PAYLOAD",
    NetworkErrorCodes.UNAUTHORIZED,
    -> R.string.upload_error_session_expired

    NetworkErrorCodes.FILE_READ_ERROR -> R.string.upload_error_file_read
    NetworkErrorCodes.POLLING_TIMEOUT -> R.string.upload_error_polling_timeout
    NetworkErrorCodes.NETWORK_ERROR,
    NetworkErrorCodes.TIMEOUT,
    -> R.string.auth_error_network

    else -> R.string.upload_error_analysis_failed
}
