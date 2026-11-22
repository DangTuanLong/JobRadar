package com.jobradar.app.data.remote

import com.jobradar.app.core.network.ApiResponse
import com.jobradar.app.data.remote.dto.HealthDto
import retrofit2.http.GET

/**
 * Retrofit contract for JobRadar backend v1 endpoints.
 * CV, profile, and matching endpoints will be added in subsequent phases.
 */
interface JobRadarApiService {
    @GET("health")
    suspend fun getHealth(): ApiResponse<HealthDto>
}
