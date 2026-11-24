package com.jobradar.app.data.remote.dto

import com.jobradar.app.domain.model.MatchResult
import com.jobradar.app.domain.model.ScoreBreakdown
import com.jobradar.app.domain.model.WebEvidence
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs matching Backend `MatchResultResponse` (`backend/app/schemas/match.py`).
 */
@Serializable
data class ScoreBreakdownDto(
    val skills: Int = 0,
    val experience: Int = 0,
    val projects: Int = 0,
)

@Serializable
data class WebEvidenceDto(
    val title: String,
    val url: String,
    val snippet: String = "",
)

@Serializable
data class MatchResultDto(
    val id: String,
    @SerialName("job_id") val jobId: String,
    @SerialName("profile_id") val profileId: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("job_posting_id") val jobPostingId: String? = null,
    @SerialName("match_score") val matchScore: Int = 0,
    @SerialName("score_breakdown") val scoreBreakdown: ScoreBreakdownDto = ScoreBreakdownDto(),
    @SerialName("matched_skills") val matchedSkills: List<String> = emptyList(),
    @SerialName("missing_skills") val missingSkills: List<String> = emptyList(),
    val reasoning: String? = null,
    @SerialName("web_evidence") val webEvidence: List<WebEvidenceDto> = emptyList(),
    @SerialName("company_name") val companyName: String? = null,
    @SerialName("company_website") val companyWebsite: String? = null,
    @SerialName("company_location") val companyLocation: String? = null,
    @SerialName("company_industry") val companyIndustry: String? = null,
    @SerialName("company_size_range") val companySizeRange: String? = null,
    @SerialName("job_title") val jobTitle: String? = null,
    @SerialName("job_level") val jobLevel: String? = null,
    @SerialName("job_source_url") val jobSourceUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

fun ScoreBreakdownDto.toDomain(): ScoreBreakdown = ScoreBreakdown(
    skills = skills.coerceIn(0, 100),
    experience = experience.coerceIn(0, 100),
    projects = projects.coerceIn(0, 100),
)

fun WebEvidenceDto.toDomain(): WebEvidence = WebEvidence(
    title = title,
    url = url,
    snippet = snippet,
)

fun MatchResultDto.toDomain(): MatchResult = MatchResult(
    id = id,
    jobId = jobId,
    profileId = profileId,
    companyId = companyId,
    jobPostingId = jobPostingId,
    companyName = companyName,
    companyWebsite = companyWebsite,
    companyLocation = companyLocation,
    companyIndustry = companyIndustry,
    companySizeRange = companySizeRange,
    jobTitle = jobTitle,
    jobLevel = jobLevel,
    jobSourceUrl = jobSourceUrl,
    matchScore = matchScore.coerceIn(0, 100),
    scoreBreakdown = scoreBreakdown.toDomain(),
    matchedSkills = matchedSkills,
    missingSkills = missingSkills,
    reasoning = reasoning,
    webEvidence = webEvidence.map { it.toDomain() },
    createdAt = createdAt,
)
