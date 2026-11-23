package com.jobradar.app.ui.profile

import androidx.annotation.StringRes
import com.jobradar.app.R
import com.jobradar.app.core.network.NetworkErrorCodes
import com.jobradar.app.domain.model.ExperienceType
import com.jobradar.app.domain.model.SkillLevel

const val PROFILE_NOT_FOUND_CODE = "PROFILE_NOT_FOUND"

@StringRes
fun mapProfileErrorMessageRes(
    code: String?,
    @StringRes fallbackRes: Int = R.string.profile_error_load,
): Int = when (code) {
    PROFILE_NOT_FOUND_CODE -> R.string.profile_error_not_found
    "AUTH_MISSING_TOKEN",
    "AUTH_INVALID_TOKEN",
    "AUTH_VERIFICATION_FAILED",
    "AUTH_INVALID_PAYLOAD",
    NetworkErrorCodes.UNAUTHORIZED,
    -> R.string.upload_error_session_expired

    "AI_RATE_LIMITED" -> R.string.upload_error_ai_rate_limited
    "AI_SERVICE_UNAVAILABLE",
    "AI_SERVICE_ERROR",
    "AI_CONFIG_ERROR",
    -> R.string.upload_error_ai_service

    NetworkErrorCodes.NETWORK_ERROR,
    NetworkErrorCodes.TIMEOUT,
    -> R.string.auth_error_network

    NetworkErrorCodes.POLLING_TIMEOUT -> R.string.upload_error_polling_timeout
    else -> fallbackRes
}

@StringRes
fun skillLevelLabelRes(level: SkillLevel): Int = when (level) {
    SkillLevel.BEGINNER -> R.string.skill_level_beginner
    SkillLevel.INTERMEDIATE -> R.string.skill_level_intermediate
    SkillLevel.ADVANCED -> R.string.skill_level_advanced
    SkillLevel.EXPERT -> R.string.skill_level_expert
}

@StringRes
fun experienceTypeLabelRes(type: ExperienceType): Int = when (type) {
    ExperienceType.WORK -> R.string.exp_type_work
    ExperienceType.PROJECT -> R.string.exp_type_project
    ExperienceType.EDUCATION -> R.string.exp_type_education
    ExperienceType.OTHER -> R.string.exp_type_other
}
