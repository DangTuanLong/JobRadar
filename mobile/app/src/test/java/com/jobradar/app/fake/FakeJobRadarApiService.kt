package com.jobradar.app.fake

import com.jobradar.app.core.network.ApiResponse
import com.jobradar.app.data.remote.JobRadarApiService
import com.jobradar.app.data.remote.dto.AnalysisJobDto
import com.jobradar.app.data.remote.dto.CandidateProfileDto
import com.jobradar.app.data.remote.dto.CandidateProfileUpdateRequestDto
import com.jobradar.app.data.remote.dto.HealthDto
import com.jobradar.app.data.remote.dto.MatchResultDto
import okhttp3.MultipartBody
import retrofit2.Response

class FakeJobRadarApiService : JobRadarApiService {

    var lastCvPart: MultipartBody.Part? = null
        private set
    var lastLinkedInPart: MultipartBody.Part? = null
        private set
    var lastProfileUpdateRequest: CandidateProfileUpdateRequestDto? = null
        private set
    var analyzeCalls = 0
        private set
    var getJobCalls = 0
        private set
    var getProfileCalls = 0
        private set
    var updateProfileCalls = 0
        private set
    var rerunMatchesCalls = 0
        private set
    var getMatchesCalls = 0
        private set
    var lastMatchesJobId: String? = null
        private set

    private val queuedPollResponses = ArrayDeque<suspend (String) -> Response<ApiResponse<AnalysisJobDto>>>()

    var analyzeHandler: suspend () -> Response<ApiResponse<AnalysisJobDto>> = {
        error("analyzeHandler not configured")
    }

    var fallbackPollHandler: suspend (String) -> Response<ApiResponse<AnalysisJobDto>> = {
        error("No more queued poll responses")
    }

    var getProfileHandler: suspend () -> Response<ApiResponse<CandidateProfileDto>> = {
        error("getProfileHandler not configured")
    }

    var updateProfileHandler: suspend (CandidateProfileUpdateRequestDto) -> Response<ApiResponse<CandidateProfileDto>> = {
        error("updateProfileHandler not configured")
    }

    var rerunMatchesHandler: suspend () -> Response<ApiResponse<AnalysisJobDto>> = {
        error("rerunMatchesHandler not configured")
    }

    var getMatchesHandler: suspend (String) -> Response<ApiResponse<List<MatchResultDto>>> = {
        error("getMatchesHandler not configured")
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

    override suspend fun getMyProfile(): Response<ApiResponse<CandidateProfileDto>> {
        getProfileCalls++
        return getProfileHandler()
    }

    override suspend fun updateMyProfile(
        request: CandidateProfileUpdateRequestDto,
    ): Response<ApiResponse<CandidateProfileDto>> {
        updateProfileCalls++
        lastProfileUpdateRequest = request
        return updateProfileHandler(request)
    }

    override suspend fun rerunMatches(): Response<ApiResponse<AnalysisJobDto>> {
        rerunMatchesCalls++
        return rerunMatchesHandler()
    }

    override suspend fun getMatches(jobId: String): Response<ApiResponse<List<MatchResultDto>>> {
        getMatchesCalls++
        lastMatchesJobId = jobId
        return getMatchesHandler(jobId)
    }
}
