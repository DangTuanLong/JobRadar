package com.jobradar.app.data.repository

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.core.network.safeApiCall
import com.jobradar.app.data.remote.JobRadarApiService
import com.jobradar.app.data.remote.dto.toDomain
import com.jobradar.app.domain.model.BackendHealth
import com.jobradar.app.domain.repository.HealthRepository
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthRepositoryImpl @Inject constructor(
    private val apiService: JobRadarApiService,
    private val json: Json,
) : HealthRepository {

    override suspend fun checkHealth(): AppResult<BackendHealth> =
        when (val response = safeApiCall(json) { apiService.getHealth() }) {
            is AppResult.Success -> AppResult.Success(response.data.toDomain())
            is AppResult.Error -> response
        }
}
