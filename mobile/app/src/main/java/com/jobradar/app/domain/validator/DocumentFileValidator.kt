package com.jobradar.app.domain.validator

import com.jobradar.app.domain.model.SelectedDocument

enum class DocumentValidationError {
    EMPTY_FILE,
    FILE_TOO_LARGE,
    UNSUPPORTED_CV_FORMAT,
    UNSUPPORTED_LINKEDIN_FORMAT,
}

object DocumentFileValidator {
    const val MAX_FILE_SIZE_BYTES: Long = 5L * 1024L * 1024L

    const val MIME_PDF = "application/pdf"
    const val MIME_PNG = "image/png"
    const val MIME_JPEG = "image/jpeg"
    const val MIME_JPG_ALIAS = "image/jpg"
    const val MIME_OCTET_STREAM = "application/octet-stream"

    private val ALLOWED_CV_MIME_TYPES = setOf(MIME_PDF, MIME_PNG, MIME_JPEG)

    fun resolveEffectiveMimeType(rawMimeType: String?, displayName: String): String {
        val normalized = rawMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()

        return when {
            normalized == MIME_JPG_ALIAS -> MIME_JPEG
            normalized.isNotEmpty() && normalized != MIME_OCTET_STREAM -> normalized
            else -> inferMimeFromExtension(displayName) ?: normalized
        }
    }

    fun validateCvDocument(document: SelectedDocument): DocumentValidationError? {
        if (document.sizeBytes <= 0L) return DocumentValidationError.EMPTY_FILE
        if (document.sizeBytes > MAX_FILE_SIZE_BYTES) return DocumentValidationError.FILE_TOO_LARGE
        val mime = resolveEffectiveMimeType(document.mimeType, document.displayName)
        return if (mime in ALLOWED_CV_MIME_TYPES) {
            null
        } else {
            DocumentValidationError.UNSUPPORTED_CV_FORMAT
        }
    }

    fun validateLinkedInPdf(document: SelectedDocument): DocumentValidationError? {
        if (document.sizeBytes <= 0L) return DocumentValidationError.EMPTY_FILE
        if (document.sizeBytes > MAX_FILE_SIZE_BYTES) return DocumentValidationError.FILE_TOO_LARGE
        val mime = resolveEffectiveMimeType(document.mimeType, document.displayName)
        return if (mime == MIME_PDF) {
            null
        } else {
            DocumentValidationError.UNSUPPORTED_LINKEDIN_FORMAT
        }
    }

    private fun inferMimeFromExtension(displayName: String): String? {
        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "pdf" -> MIME_PDF
            "png" -> MIME_PNG
            "jpg", "jpeg" -> MIME_JPEG
            else -> null
        }
    }
}
