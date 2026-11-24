package com.jobradar.app.ui.matches

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.jobradar.app.domain.model.MatchResult
import com.jobradar.app.domain.model.MatchSortOption

private val LEVEL_ORDER = listOf("intern", "fresher", "junior", "mid", "senior", "lead")

@Immutable
data class MatchesUiState(
    val contentState: MatchesContentState = MatchesContentState.NoJob,
    val activeJobId: String? = null,
    val searchQuery: String = "",
    val selectedLevel: String? = null,
    val sortOption: MatchSortOption = MatchSortOption.OVERALL_SCORE,
    val isRefreshing: Boolean = false,
) {
    val availableLevels: List<String>
        get() = when (contentState) {
            is MatchesContentState.Loaded -> extractAvailableLevels(contentState.allMatches)
            else -> emptyList()
        }

    val displayedMatches: List<MatchResult>
        get() = when (contentState) {
            is MatchesContentState.Loaded -> filterAndSortMatches(
                matches = contentState.allMatches,
                searchQuery = searchQuery,
                selectedLevel = selectedLevel,
                sortOption = sortOption,
            )

            else -> emptyList()
        }

    val hasActiveFilters: Boolean
        get() = searchQuery.isNotBlank() || selectedLevel != null
}

sealed interface MatchesContentState {
    data object NoJob : MatchesContentState

    data object Loading : MatchesContentState

    data object Empty : MatchesContentState

    data class Loaded(val allMatches: List<MatchResult>) : MatchesContentState

    data class Error(@param:StringRes val messageRes: Int) : MatchesContentState
}

fun extractAvailableLevels(matches: List<MatchResult>): List<String> =
    matches.mapNotNull { it.jobLevel?.trim()?.lowercase()?.takeIf { lvl -> lvl.isNotEmpty() } }
        .distinct()
        .sortedWith(
            compareBy<String> { level ->
                val idx = LEVEL_ORDER.indexOf(level)
                if (idx >= 0) idx else Int.MAX_VALUE
            }.thenBy { it },
        )

fun filterAndSortMatches(
    matches: List<MatchResult>,
    searchQuery: String,
    selectedLevel: String?,
    sortOption: MatchSortOption,
): List<MatchResult> {
    val trimmedQuery = searchQuery.trim()
    val filtered = matches.filter { match ->
        val matchesLevel = selectedLevel == null ||
            match.jobLevel.equals(selectedLevel, ignoreCase = true)
        val matchesQuery = trimmedQuery.isEmpty() ||
            match.companyName.orEmpty().contains(trimmedQuery, ignoreCase = true) ||
            match.jobTitle.orEmpty().contains(trimmedQuery, ignoreCase = true) ||
            match.matchedSkills.any { skill -> skill.contains(trimmedQuery, ignoreCase = true) }
        matchesLevel && matchesQuery
    }

    return when (sortOption) {
        MatchSortOption.OVERALL_SCORE -> filtered.sortedWith(
            compareByDescending<MatchResult> { it.matchScore }
                .thenByDescending { it.scoreBreakdown.skills },
        )

        MatchSortOption.SKILLS_SCORE -> filtered.sortedWith(
            compareByDescending<MatchResult> { it.scoreBreakdown.skills }
                .thenByDescending { it.matchScore },
        )
    }
}
