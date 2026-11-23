package com.jobradar.app.data.repository

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.core.network.safeApiCall
import com.jobradar.app.data.remote.JobRadarApiService
import com.jobradar.app.data.remote.dto.toDomain
import com.jobradar.app.data.remote.dto.toDto
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.CandidateProfile
import com.jobradar.app.domain.model.CandidateProfileUpdate
import com.jobradar.app.domain.repository.ProfileRepository
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val apiService: JobRadarApiService,
    private val json: Json,
) : ProfileRepository {

    override suspend fun getMyProfile(): AppResult<CandidateProfile> =
        when (val result = safeApiCall(json) { apiService.getMyProfile() }) {
            is AppResult.Success -> AppResult.Success(result.data.toDomain())
            is AppResult.Error -> result
        }

    override suspend fun updateMyProfile(
        update: CandidateProfileUpdate,
    ): AppResult<CandidateProfile> =
        when (val result = safeApiCall(json) { apiService.updateMyProfile(update.toDto()) }) {
            is AppResult.Success -> AppResult.Success(result.data.toDomain())
            is AppResult.Error -> result
        }

    override suspend fun rerunMatches(): AppResult<AnalysisJob> =
        when (val result = safeApiCall(json) { apiService.rerunMatches() }) {
            is AppResult.Success -> AppResult.Success(result.data.toDomain())
            is AppResult.Error -> result
        }
}
