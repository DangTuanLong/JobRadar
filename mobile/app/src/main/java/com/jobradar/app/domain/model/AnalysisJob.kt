package com.jobradar.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class AnalysisJob(
    val id: String,
    val userId: String,
    val profileId: String?,
    val status: AnalysisJobStatus,
    val progressPercent: Int,
    val currentStepMessage: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: String?,
    val updatedAt: String?,
)
