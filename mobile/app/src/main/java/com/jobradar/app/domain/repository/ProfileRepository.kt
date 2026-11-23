package com.jobradar.app.domain.repository

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.CandidateProfile
import com.jobradar.app.domain.model.CandidateProfileUpdate

interface ProfileRepository {
    suspend fun getMyProfile(): AppResult<CandidateProfile>

    suspend fun updateMyProfile(update: CandidateProfileUpdate): AppResult<CandidateProfile>

    suspend fun rerunMatches(): AppResult<AnalysisJob>
}
