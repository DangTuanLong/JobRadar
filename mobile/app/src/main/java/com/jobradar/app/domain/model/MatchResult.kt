package com.jobradar.app.domain.model

import androidx.compose.runtime.Immutable
import java.net.URI
import java.net.URISyntaxException

@Immutable
data class ScoreBreakdown(
    val skills: Int,
    val experience: Int,
    val projects: Int,
)

@Immutable
data class WebEvidence(
    val title: String,
    val url: String,
    val snippet: String,
) {
    val hostDomain: String?
        get() = extractHostDomain(url)

    val isSafeHttpUrl: Boolean
        get() = isSafeWebUrl(url)
}

@Immutable
data class MatchResult(
    val id: String,
    val jobId: String,
    val profileId: String,
    val companyId: String,
    val jobPostingId: String?,
    val companyName: String?,
    val companyWebsite: String?,
    val companyLocation: String?,
    val companyIndustry: String?,
    val companySizeRange: String?,
    val jobTitle: String?,
    val jobLevel: String?,
    val jobSourceUrl: String?,
    val matchScore: Int,
    val scoreBreakdown: ScoreBreakdown,
    val matchedSkills: List<String>,
    val missingSkills: List<String>,
    val reasoning: String?,
    val webEvidence: List<WebEvidence>,
    val createdAt: String?,
) {
    val hasSafeCompanyWebsite: Boolean
        get() = isSafeWebUrl(companyWebsite)
}

enum class MatchSortOption {
    OVERALL_SCORE,
    SKILLS_SCORE,
}

fun isSafeWebUrl(rawUrl: String?): Boolean {
    val trimmed = rawUrl?.trim() ?: return false
    if (trimmed.isEmpty()) return false
    return try {
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: URISyntaxException) {
        false
    }
}

private fun extractHostDomain(rawUrl: String): String? = try {
    URI(rawUrl.trim()).host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
} catch (_: URISyntaxException) {
    null
}
