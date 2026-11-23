package com.jobradar.app.data.remote

import com.jobradar.app.core.network.ApiResponse
import com.jobradar.app.data.remote.dto.AnalysisJobDto
import com.jobradar.app.data.remote.dto.HealthDto
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * Retrofit contract for JobRadar backend v1 endpoints.
 * Profile and matching endpoints will be added in subsequent phases.
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
}
