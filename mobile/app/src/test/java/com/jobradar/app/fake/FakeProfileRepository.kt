package com.jobradar.app.fake

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.CandidateProfile
import com.jobradar.app.domain.model.CandidateProfileUpdate
import com.jobradar.app.domain.repository.ProfileRepository

class FakeProfileRepository : ProfileRepository {

    var getProfileCalls = 0
        private set
    var updateProfileCalls = 0
        private set
    var rerunMatchesCalls = 0
        private set
    var lastUpdateRequest: CandidateProfileUpdate? = null
        private set

    var getProfileProvider: suspend () -> AppResult<CandidateProfile> = {
        error("getProfileProvider not configured")
    }

    var updateProfileProvider: suspend (CandidateProfileUpdate) -> AppResult<CandidateProfile> = {
        error("updateProfileProvider not configured")
    }

    var rerunMatchesProvider: suspend () -> AppResult<AnalysisJob> = {
        error("rerunMatchesProvider not configured")
    }

    override suspend fun getMyProfile(): AppResult<CandidateProfile> {
        getProfileCalls++
        return getProfileProvider()
    }

    override suspend fun updateMyProfile(
        update: CandidateProfileUpdate,
    ): AppResult<CandidateProfile> {
        updateProfileCalls++
        lastUpdateRequest = update
        return updateProfileProvider(update)
    }

    override suspend fun rerunMatches(): AppResult<AnalysisJob> {
        rerunMatchesCalls++
        return rerunMatchesProvider()
    }
}
