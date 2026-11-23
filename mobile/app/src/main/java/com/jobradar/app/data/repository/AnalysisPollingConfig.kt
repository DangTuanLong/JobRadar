package com.jobradar.app.data.repository

data class AnalysisPollingConfig(
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    val maxConsecutiveTransientErrors: Int = DEFAULT_MAX_CONSECUTIVE_TRANSIENT_ERRORS,
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
) {
    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 2_500L
        const val DEFAULT_MAX_CONSECUTIVE_TRANSIENT_ERRORS = 3
        const val DEFAULT_MAX_DURATION_MS = 300_000L
    }
}
