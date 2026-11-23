package com.jobradar.app.domain.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the latest completed analysis job ID shared across tabs in the main shell.
 */
@Singleton
class ActiveAnalysisStore @Inject constructor() {

    private val _latestCompletedJobId = MutableStateFlow<String?>(null)
    val latestCompletedJobId: StateFlow<String?> = _latestCompletedJobId.asStateFlow()

    fun setLatestCompletedJobId(jobId: String) {
        if (jobId.isNotBlank()) {
            _latestCompletedJobId.value = jobId
        }
    }

    fun clear() {
        _latestCompletedJobId.value = null
    }
}
