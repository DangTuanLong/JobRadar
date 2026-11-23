package com.jobradar.app.ui.profile

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.CandidateProfile
import com.jobradar.app.domain.model.CandidateProfileUpdate
import com.jobradar.app.domain.model.ExternalLinks
import com.jobradar.app.domain.model.SkillItem
import com.jobradar.app.domain.model.SkillLevel
import java.util.Locale

@Immutable
data class ProfileUiState(
    val contentState: ProfileContentState = ProfileContentState.Loading,
    val isRefreshing: Boolean = false,
    val editDraft: ProfileEditDraft? = null,
    val rerunState: RerunMatchingState = RerunMatchingState.Idle,
    val suggestRerun: Boolean = false,
) {
    val isRerunActive: Boolean
        get() = rerunState is RerunMatchingState.Starting || rerunState is RerunMatchingState.Running
}

sealed interface ProfileContentState {
    data object Loading : ProfileContentState

    data object Empty : ProfileContentState

    data class Loaded(val profile: CandidateProfile) : ProfileContentState

    data class Error(@param:StringRes val messageRes: Int) : ProfileContentState
}

@Immutable
data class ProfileEditDraft(
    val fullName: String,
    val headline: String,
    val yearsOfExperienceText: String,
    val profileSummary: String,
    val githubUrl: String,
    val portfolioUrl: String,
    val linkedinUrl: String,
    val otherLinks: List<String>,
    val skills: List<SkillItem>,
    val newSkillName: String = "",
    val newSkillLevel: SkillLevel = SkillLevel.INTERMEDIATE,
    @param:StringRes val yearsError: Int? = null,
    @param:StringRes val skillInputError: Int? = null,
    val isSaving: Boolean = false,
) {
    fun toUpdatePayload(parsedYears: Double): CandidateProfileUpdate = CandidateProfileUpdate(
        fullName = fullName.trim(),
        headline = headline.trim(),
        yearsOfExperience = parsedYears,
        skills = skills,
        experiences = null,
        externalLinks = ExternalLinks(
            github = githubUrl.trim(),
            portfolio = portfolioUrl.trim(),
            linkedin = linkedinUrl.trim(),
            other = otherLinks,
        ),
        profileSummary = profileSummary.trim(),
    )

    companion object {
        fun from(profile: CandidateProfile): ProfileEditDraft = ProfileEditDraft(
            fullName = profile.fullName.orEmpty(),
            headline = profile.headline.orEmpty(),
            yearsOfExperienceText = formatYearsInput(profile.yearsOfExperience),
            profileSummary = profile.profileSummary.orEmpty(),
            githubUrl = profile.externalLinks.github.orEmpty(),
            portfolioUrl = profile.externalLinks.portfolio.orEmpty(),
            linkedinUrl = profile.externalLinks.linkedin.orEmpty(),
            otherLinks = profile.externalLinks.other,
            skills = profile.skills,
        )
    }
}

sealed interface RerunMatchingState {
    data object Idle : RerunMatchingState

    data object Starting : RerunMatchingState

    data class Running(val job: AnalysisJob) : RerunMatchingState

    data class Completed(val job: AnalysisJob) : RerunMatchingState

    data class Failed(
        @param:StringRes val messageRes: Int,
        val detailMessage: String? = null,
    ) : RerunMatchingState
}

sealed interface ProfileEvent {
    data class ShowMessage(@param:StringRes val messageRes: Int) : ProfileEvent

    data class RerunCompleted(@param:StringRes val messageRes: Int) : ProfileEvent
}

fun formatYearsInput(years: Double): String {
    val safe = years.coerceAtLeast(0.0)
    return if (safe % 1.0 == 0.0) {
        safe.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", safe)
    }
}
