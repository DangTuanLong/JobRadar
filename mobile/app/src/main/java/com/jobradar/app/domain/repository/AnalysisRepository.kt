package com.jobradar.app.domain.repository

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.SelectedDocument
import kotlinx.coroutines.flow.Flow

interface AnalysisRepository {
    suspend fun analyzeCv(
        cvDocument: SelectedDocument,
        linkedInPdf: SelectedDocument? = null,
    ): AppResult<AnalysisJob>

    fun observeJobStatus(jobId: String): Flow<AppResult<AnalysisJob>>
}
