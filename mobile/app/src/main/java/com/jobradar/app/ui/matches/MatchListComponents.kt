package com.jobradar.app.ui.matches

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobradar.app.R
import com.jobradar.app.domain.model.MatchResult
import com.jobradar.app.domain.model.MatchSortOption

private const val MAX_PREVIEW_SKILLS = 3

@Composable
fun MatchesFilterHeader(
    searchQuery: String,
    availableLevels: List<String>,
    selectedLevel: String?,
    sortOption: MatchSortOption,
    resultCount: Int,
    onSearchQueryChange: (String) -> Unit,
    onLevelSelected: (String?) -> Unit,
    onSortOptionChange: (MatchSortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            placeholder = { Text(text = stringResource(R.string.matches_search_placeholder)) },
            leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                    }
                }
            } else {
                null
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        if (availableLevels.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = selectedLevel == null,
                    onClick = { onLevelSelected(null) },
                    label = { Text(text = stringResource(R.string.matches_filter_all)) },
                )
                availableLevels.forEach { level ->
                    FilterChip(
                        selected = selectedLevel.equals(level, ignoreCase = true),
                        onClick = { onLevelSelected(level) },
                        label = { Text(text = formatJobLevelLabel(level) ?: level) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.matches_result_count, resultCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = sortOption == MatchSortOption.OVERALL_SCORE,
                    onClick = { onSortOptionChange(MatchSortOption.OVERALL_SCORE) },
                    label = { Text(text = stringResource(R.string.matches_sort_overall)) },
                )
                FilterChip(
                    selected = sortOption == MatchSortOption.SKILLS_SCORE,
                    onClick = { onSortOptionChange(MatchSortOption.SKILLS_SCORE) },
                    label = { Text(text = stringResource(R.string.matches_sort_skills)) },
                )
            }
        }
    }
}

@Composable
fun MatchResultCard(
    rank: Int,
    match: MatchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.matches_rank_format, rank),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Outlined.Business,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = match.companyName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.matches_unknown_company),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = match.jobTitle?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.matches_unknown_role),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                MatchScoreBadge(score = match.matchScore)
            }

            val metaParts = listOfNotNull(
                formatJobLevelLabel(match.jobLevel),
                match.companyLocation?.takeIf { it.isNotBlank() },
            )
            if (metaParts.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (match.matchedSkills.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    match.matchedSkills.take(MAX_PREVIEW_SKILLS).forEach { skill ->
                        SkillPill(label = skill)
                    }
                    val remaining = match.matchedSkills.size - MAX_PREVIEW_SKILLS
                    if (remaining > 0) {
                        SkillPill(label = stringResource(R.string.matches_extra_skills_count, remaining))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val missingLabel = if (match.missingSkills.isEmpty()) {
                    stringResource(R.string.matches_no_missing_short)
                } else {
                    stringResource(R.string.matches_missing_count, match.missingSkills.size)
                }
                Text(
                    text = missingLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (match.missingSkills.isEmpty()) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (match.webEvidence.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.matches_web_evidence_count, match.webEvidence.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}
