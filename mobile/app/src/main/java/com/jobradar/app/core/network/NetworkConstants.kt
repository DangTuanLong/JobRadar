package com.jobradar.app.core.network

object NetworkConstants {
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 60L

    const val HEADER_AUTHORIZATION = "Authorization"
    const val BEARER_PREFIX = "Bearer "
    const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

    const val PART_CV_FILE = "cv_file"
    const val PART_LINKEDIN_PDF = "linkedin_pdf"
}
