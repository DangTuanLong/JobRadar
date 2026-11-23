package com.jobradar.app.domain.model

import androidx.compose.runtime.Immutable

enum class SkillCategory(val wireValue: String) {
    LANGUAGE("language"),
    FRAMEWORK("framework"),
    DATABASE("database"),
    CLOUD_DEVOPS("cloud_devops"),
    AI_ML("ai_ml"),
    TOOL("tool"),
    SOFT_SKILL("soft_skill"),
    OTHER("other"),
    ;

    companion object {
        fun fromWireValue(raw: String): SkillCategory {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.wireValue == normalized } ?: OTHER
        }
    }
}

enum class SkillLevel(val wireValue: String) {
    BEGINNER("beginner"),
    INTERMEDIATE("intermediate"),
    ADVANCED("advanced"),
    EXPERT("expert"),
    ;

    companion object {
        fun fromWireValue(raw: String): SkillLevel {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.wireValue == normalized } ?: INTERMEDIATE
        }
    }
}

enum class ExperienceType(val wireValue: String) {
    WORK("work"),
    PROJECT("project"),
    EDUCATION("education"),
    OTHER("other"),
    ;

    companion object {
        fun fromWireValue(raw: String): ExperienceType {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.wireValue == normalized } ?: WORK
        }
    }
}

@Immutable
data class SkillItem(
    val name: String,
    val category: SkillCategory = SkillCategory.OTHER,
    val level: SkillLevel = SkillLevel.INTERMEDIATE,
    val verifiedBy: List<String> = emptyList(),
) {
    val isGithubVerified: Boolean
        get() = verifiedBy.any { it.equals(GITHUB_VERIFICATION_SOURCE, ignoreCase = true) }

    companion object {
        const val GITHUB_VERIFICATION_SOURCE = "github"
    }
}

@Immutable
data class ExperienceItem(
    val title: String,
    val organization: String? = null,
    val itemType: ExperienceType = ExperienceType.WORK,
    val duration: String? = null,
    val description: String? = null,
    val technologies: List<String> = emptyList(),
)

@Immutable
data class ExternalLinks(
    val github: String? = null,
    val portfolio: String? = null,
    val linkedin: String? = null,
    val other: List<String> = emptyList(),
) {
    val hasAnyLink: Boolean
        get() = !github.isNullOrBlank() ||
            !portfolio.isNullOrBlank() ||
            !linkedin.isNullOrBlank() ||
            other.isNotEmpty()
}

@Immutable
data class EnrichmentSummary(
    val githubUsername: String? = null,
    val githubPublicRepos: Int = 0,
    val githubTotalStars: Int = 0,
    val githubTopLanguages: List<String> = emptyList(),
    val githubHighlights: List<String> = emptyList(),
    val portfolioSummary: String? = null,
    val linkedinVerified: Boolean = false,
)

@Immutable
data class CandidateProfile(
    val id: String,
    val userId: String,
    val fullName: String?,
    val headline: String?,
    val yearsOfExperience: Double,
    val cvFileUrl: String?,
    val skills: List<SkillItem>,
    val experiences: List<ExperienceItem>,
    val externalLinks: ExternalLinks,
    val enrichmentSummary: EnrichmentSummary,
    val profileSummary: String?,
    val createdAt: String?,
    val updatedAt: String?,
) {
    val githubVerifiedCount: Int
        get() = skills.count { it.isGithubVerified }

    val sortedSkills: List<SkillItem>
        get() = skills.sortedByDescending { it.isGithubVerified }
}

@Immutable
data class CandidateProfileUpdate(
    val fullName: String? = null,
    val headline: String? = null,
    val yearsOfExperience: Double? = null,
    val skills: List<SkillItem>? = null,
    val experiences: List<ExperienceItem>? = null,
    val externalLinks: ExternalLinks? = null,
    val profileSummary: String? = null,
)
