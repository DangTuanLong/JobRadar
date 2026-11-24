package com.jobradar.app.data.repository

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.core.network.safeApiCall
import com.jobradar.app.data.cache.MatchCacheStore
import com.jobradar.app.data.remote.JobRadarApiService
import com.jobradar.app.data.remote.dto.toDomain
import com.jobradar.app.domain.model.MatchResult
import com.jobradar.app.domain.repository.MatchRepository
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

const val MATCH_NOT_FOUND_CODE = "MATCH_NOT_FOUND"

@Singleton
class MatchRepositoryImpl @Inject constructor(
    private val apiService: JobRadarApiService,
    private val json: Json,
    private val cacheStore: MatchCacheStore,
) : MatchRepository {

    override suspend fun getMatches(
        jobId: String,
        forceRefresh: Boolean,
    ): AppResult<List<MatchResult>> {
        if (!forceRefresh) {
            cacheStore.get(jobId)?.let { cached ->
                return AppResult.Success(cached)
            }
        }

        return when (val result = safeApiCall(json) { apiService.getMatches(jobId) }) {
            is AppResult.Success -> {
                val sorted = result.data
                    .map { it.toDomain() }
                    .sortedWith(
                        compareByDescending<MatchResult> { it.matchScore }
                            .thenByDescending { it.scoreBreakdown.skills },
                    )
                cacheStore.put(jobId, sorted)
                AppResult.Success(sorted)
            }

            is AppResult.Error -> result
        }
    }

    override suspend fun getMatchDetail(
        jobId: String,
        matchId: String,
    ): AppResult<MatchResult> {
        cacheStore.get(jobId)?.firstOrNull { it.id == matchId }?.let { cached ->
            return AppResult.Success(cached)
        }

        return when (val listResult = getMatches(jobId = jobId, forceRefresh = false)) {
            is AppResult.Success -> {
                val found = listResult.data.firstOrNull { it.id == matchId }
                if (found != null) {
                    AppResult.Success(found)
                } else {
                    AppResult.Error(
                        code = MATCH_NOT_FOUND_CODE,
                        message = "Match detail not found.",
                    )
                }
            }

            is AppResult.Error -> listResult
        }
    }

    override fun clearCache() {
        cacheStore.clear()
    }
}
