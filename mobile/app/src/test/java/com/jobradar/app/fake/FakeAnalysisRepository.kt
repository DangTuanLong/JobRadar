package com.jobradar.app.fake

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.SelectedDocument
import com.jobradar.app.domain.repository.AnalysisRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class FakeAnalysisRepository : AnalysisRepository {

    var analyzeCalls = 0
        private set
    var observeCalls = 0
        private set
    var lastCvDocument: SelectedDocument? = null
        private set
    var lastLinkedInPdf: SelectedDocument? = null
        private set

    var analyzeResultProvider: suspend () -> AppResult<AnalysisJob> = {
        error("analyzeResultProvider not configured")
    }

    var observeFlowProvider: (String) -> Flow<AppResult<AnalysisJob>> = {
        emptyFlow()
    }

    override suspend fun analyzeCv(
        cvDocument: SelectedDocument,
        linkedInPdf: SelectedDocument?,
    ): AppResult<AnalysisJob> {
        analyzeCalls++
        lastCvDocument = cvDocument
        lastLinkedInPdf = linkedInPdf
        return analyzeResultProvider()
    }

    override fun observeJobStatus(jobId: String): Flow<AppResult<AnalysisJob>> {
        observeCalls++
        return observeFlowProvider(jobId)
    }
}
