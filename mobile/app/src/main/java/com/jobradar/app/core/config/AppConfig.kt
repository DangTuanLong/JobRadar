package com.jobradar.app.core.config

/**
 * Immutable runtime configuration populated from BuildConfig fields.
 */
data class AppConfig(
    val supabaseUrl: String,
    val supabasePublishableKey: String,
    val apiBaseUrl: String,
) {
    val isSupabaseConfigured: Boolean
        get() = supabaseUrl.isNotBlank() && supabasePublishableKey.isNotBlank()

    val normalizedApiBaseUrl: String
        get() {
            val trimmed = apiBaseUrl.trim().ifEmpty { DEFAULT_API_BASE_URL }
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }

    companion object {
        const val DEFAULT_API_BASE_URL = "http://10.0.2.2:8000/api/v1/"
    }
}
