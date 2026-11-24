package com.jobradar.app.fake

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.data.repository.MATCH_NOT_FOUND_CODE
import com.jobradar.app.domain.model.MatchResult
import com.jobradar.app.domain.repository.MatchRepository

class FakeMatchRepository : MatchRepository {

    var getMatchesCalls = 0
        private set
    var getDetailCalls = 0
        private set
    var lastForceRefresh = false
        private set

    var matchesProvider: suspend (String, Boolean) -> AppResult<List<MatchResult>> = { _, _ ->
        error("matchesProvider not configured")
    }

    var matchDetailProvider: (suspend (String, String) -> AppResult<MatchResult>)? = null

    override suspend fun getMatches(
        jobId: String,
        forceRefresh: Boolean,
    ): AppResult<List<MatchResult>> {
        getMatchesCalls++
        lastForceRefresh = forceRefresh
        return matchesProvider(jobId, forceRefresh)
    }

    override suspend fun getMatchDetail(
        jobId: String,
        matchId: String,
    ): AppResult<MatchResult> {
        getDetailCalls++
        matchDetailProvider?.let { return it(jobId, matchId) }
        return when (val listResult = matchesProvider(jobId, false)) {
            is AppResult.Success -> {
                val found = listResult.data.firstOrNull { it.id == matchId }
                if (found != null) {
                    AppResult.Success(found)
                } else {
                    AppResult.Error(MATCH_NOT_FOUND_CODE, "Not found")
                }
            }

            is AppResult.Error -> listResult
        }
    }

    override fun clearCache() = Unit
}
