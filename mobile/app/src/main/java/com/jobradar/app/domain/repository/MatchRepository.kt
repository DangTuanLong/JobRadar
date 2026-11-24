package com.jobradar.app.domain.repository

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.MatchResult

interface MatchRepository {
    suspend fun getMatches(
        jobId: String,
        forceRefresh: Boolean = false,
    ): AppResult<List<MatchResult>>

    suspend fun getMatchDetail(
        jobId: String,
        matchId: String,
    ): AppResult<MatchResult>

    fun clearCache()
}
