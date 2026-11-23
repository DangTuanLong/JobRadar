package com.jobradar.app.fake

import com.jobradar.app.core.network.ApiResponse
import com.jobradar.app.data.remote.JobRadarApiService
import com.jobradar.app.data.remote.dto.AnalysisJobDto
import com.jobradar.app.data.remote.dto.HealthDto
import okhttp3.MultipartBody
import retrofit2.Response

class FakeJobRadarApiService : JobRadarApiService {

    var lastCvPart: MultipartBody.Part? = null
        private set
    var lastLinkedInPart: MultipartBody.Part? = null
        private set
    var analyzeCalls = 0
        private set
    var getJobCalls = 0
        private set

    private val queuedPollResponses = ArrayDeque<suspend (String) -> Response<ApiResponse<AnalysisJobDto>>>()

    var analyzeHandler: suspend () -> Response<ApiResponse<AnalysisJobDto>> = {
        error("analyzeHandler not configured")
    }

    var fallbackPollHandler: suspend (String) -> Response<ApiResponse<AnalysisJobDto>> = {
        error("No more queued poll responses")
    }

    fun enqueuePollResponses(vararg responses: suspend (String) -> Response<ApiResponse<AnalysisJobDto>>) {
        responses.forEach { queuedPollResponses.addLast(it) }
    }

    override suspend fun getHealth(): Response<ApiResponse<HealthDto>> =
        Response.success(ApiResponse(success = true, data = HealthDto(status = "ok")))

    override suspend fun analyzeCv(
        cvFile: MultipartBody.Part,
        linkedinPdf: MultipartBody.Part?,
    ): Response<ApiResponse<AnalysisJobDto>> {
        analyzeCalls++
        lastCvPart = cvFile
        lastLinkedInPart = linkedinPdf
        return analyzeHandler()
    }

    override suspend fun getAnalysisJob(jobId: String): Response<ApiResponse<AnalysisJobDto>> {
        getJobCalls++
        val next = queuedPollResponses.removeFirstOrNull() ?: fallbackPollHandler
        return next(jobId)
    }
}
