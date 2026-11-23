package com.jobradar.app.data.remote.dto

import com.jobradar.app.domain.model.CandidateProfile
import com.jobradar.app.domain.model.CandidateProfileUpdate
import com.jobradar.app.domain.model.EnrichmentSummary
import com.jobradar.app.domain.model.ExperienceItem
import com.jobradar.app.domain.model.ExperienceType
import com.jobradar.app.domain.model.ExternalLinks
import com.jobradar.app.domain.model.SkillCategory
import com.jobradar.app.domain.model.SkillItem
import com.jobradar.app.domain.model.SkillLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs matching Backend `backend/app/schemas/profile.py`.
 */
@Serializable
data class SkillItemDto(
    val name: String,
    val category: String = SkillCategory.OTHER.wireValue,
    val level: String = SkillLevel.INTERMEDIATE.wireValue,
    @SerialName("verified_by") val verifiedBy: List<String> = emptyList(),
)

@Serializable
data class ExperienceItemDto(
    val title: String,
    val organization: String? = null,
    @SerialName("item_type") val itemType: String = ExperienceType.WORK.wireValue,
    val duration: String? = null,
    val description: String? = null,
    val technologies: List<String> = emptyList(),
)

@Serializable
data class ExternalLinksDto(
    val github: String? = null,
    val portfolio: String? = null,
    val linkedin: String? = null,
    val other: List<String> = emptyList(),
)

@Serializable
data class EnrichmentSummaryDto(
    @SerialName("github_username") val githubUsername: String? = null,
    @SerialName("github_public_repos") val githubPublicRepos: Int = 0,
    @SerialName("github_total_stars") val githubTotalStars: Int = 0,
    @SerialName("github_top_languages") val githubTopLanguages: List<String> = emptyList(),
    @SerialName("github_highlights") val githubHighlights: List<String> = emptyList(),
    @SerialName("portfolio_summary") val portfolioSummary: String? = null,
    @SerialName("linkedin_verified") val linkedinVerified: Boolean = false,
    @SerialName("sources_fetched") val sourcesFetched: List<String> = emptyList(),
    @SerialName("source_statuses") val sourceStatuses: Map<String, String> = emptyMap(),
    @SerialName("fetch_warnings") val fetchWarnings: List<String> = emptyList(),
)

@Serializable
data class CandidateProfileDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("full_name") val fullName: String? = null,
    val headline: String? = null,
    @SerialName("years_of_experience") val yearsOfExperience: Double = 0.0,
    @SerialName("cv_file_url") val cvFileUrl: String? = null,
    val skills: List<SkillItemDto> = emptyList(),
    val experiences: List<ExperienceItemDto> = emptyList(),
    @SerialName("external_links") val externalLinks: ExternalLinksDto = ExternalLinksDto(),
    @SerialName("enrichment_summary") val enrichmentSummary: EnrichmentSummaryDto = EnrichmentSummaryDto(),
    @SerialName("profile_summary") val profileSummary: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class CandidateProfileUpdateRequestDto(
    @SerialName("full_name") val fullName: String? = null,
    val headline: String? = null,
    @SerialName("years_of_experience") val yearsOfExperience: Double? = null,
    val skills: List<SkillItemDto>? = null,
    val experiences: List<ExperienceItemDto>? = null,
    @SerialName("external_links") val externalLinks: ExternalLinksDto? = null,
    @SerialName("profile_summary") val profileSummary: String? = null,
)

fun SkillItemDto.toDomain(): SkillItem = SkillItem(
    name = name,
    category = SkillCategory.fromWireValue(category),
    level = SkillLevel.fromWireValue(level),
    verifiedBy = verifiedBy,
)

fun ExperienceItemDto.toDomain(): ExperienceItem = ExperienceItem(
    title = title,
    organization = organization,
    itemType = ExperienceType.fromWireValue(itemType),
    duration = duration,
    description = description,
    technologies = technologies,
)

fun ExternalLinksDto.toDomain(): ExternalLinks = ExternalLinks(
    github = github,
    portfolio = portfolio,
    linkedin = linkedin,
    other = other,
)

fun EnrichmentSummaryDto.toDomain(): EnrichmentSummary = EnrichmentSummary(
    githubUsername = githubUsername,
    githubPublicRepos = githubPublicRepos,
    githubTotalStars = githubTotalStars,
    githubTopLanguages = githubTopLanguages,
    githubHighlights = githubHighlights,
    portfolioSummary = portfolioSummary,
    linkedinVerified = linkedinVerified,
)

fun CandidateProfileDto.toDomain(): CandidateProfile = CandidateProfile(
    id = id,
    userId = userId,
    fullName = fullName,
    headline = headline,
    yearsOfExperience = yearsOfExperience,
    cvFileUrl = cvFileUrl,
    skills = skills.map { it.toDomain() },
    experiences = experiences.map { it.toDomain() },
    externalLinks = externalLinks.toDomain(),
    enrichmentSummary = enrichmentSummary.toDomain(),
    profileSummary = profileSummary,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun SkillItem.toDto(): SkillItemDto = SkillItemDto(
    name = name,
    category = category.wireValue,
    level = level.wireValue,
    verifiedBy = verifiedBy,
)

fun ExperienceItem.toDto(): ExperienceItemDto = ExperienceItemDto(
    title = title,
    organization = organization,
    itemType = itemType.wireValue,
    duration = duration,
    description = description,
    technologies = technologies,
)

fun ExternalLinks.toDto(): ExternalLinksDto = ExternalLinksDto(
    github = github,
    portfolio = portfolio,
    linkedin = linkedin,
    other = other,
)

fun CandidateProfileUpdate.toDto(): CandidateProfileUpdateRequestDto =
    CandidateProfileUpdateRequestDto(
        fullName = fullName,
        headline = headline,
        yearsOfExperience = yearsOfExperience,
        skills = skills?.map { it.toDto() },
        experiences = experiences?.map { it.toDto() },
        externalLinks = externalLinks?.toDto(),
        profileSummary = profileSummary,
    )
