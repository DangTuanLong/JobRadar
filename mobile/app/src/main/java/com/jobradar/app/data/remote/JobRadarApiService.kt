package com.jobradar.app.data.remote

import com.jobradar.app.core.network.ApiResponse
import com.jobradar.app.data.remote.dto.AnalysisJobDto
import com.jobradar.app.data.remote.dto.CandidateProfileDto
import com.jobradar.app.data.remote.dto.CandidateProfileUpdateRequestDto
import com.jobradar.app.data.remote.dto.HealthDto
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * Retrofit contract for JobRadar backend v1 endpoints.
 * Match query endpoint will be added in Prompt 4.4.
 */
interface JobRadarApiService {
    @GET("health")
    suspend fun getHealth(): Response<ApiResponse<HealthDto>>

    @Multipart
    @POST("cv/analyze")
    suspend fun analyzeCv(
        @Part cvFile: MultipartBody.Part,
        @Part linkedinPdf: MultipartBody.Part? = null,
    ): Response<ApiResponse<AnalysisJobDto>>

    @GET("jobs/{job_id}")
    suspend fun getAnalysisJob(
        @Path("job_id") jobId: String,
    ): Response<ApiResponse<AnalysisJobDto>>

    @GET("profiles/me")
    suspend fun getMyProfile(): Response<ApiResponse<CandidateProfileDto>>

    @PUT("profiles/me")
    suspend fun updateMyProfile(
        @Body request: CandidateProfileUpdateRequestDto,
    ): Response<ApiResponse<CandidateProfileDto>>

    @POST("matches/rerun")
    suspend fun rerunMatches(): Response<ApiResponse<AnalysisJobDto>>
}
