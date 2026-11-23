package com.jobradar.app.data.remote.dto

import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.AnalysisJobStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO matching Backend `AnalysisJobResponse` (`backend/app/schemas/job.py`).
 */
@Serializable
data class AnalysisJobDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("profile_id") val profileId: String? = null,
    val status: String,
    @SerialName("progress_percent") val progressPercent: Int = 0,
    @SerialName("current_step_message") val currentStepMessage: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

fun AnalysisJobDto.toDomain(): AnalysisJob = AnalysisJob(
    id = id,
    userId = userId,
    profileId = profileId,
    status = AnalysisJobStatus.fromWireValue(status),
    progressPercent = progressPercent.coerceIn(0, 100),
    currentStepMessage = currentStepMessage,
    errorCode = errorCode,
    errorMessage = errorMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
