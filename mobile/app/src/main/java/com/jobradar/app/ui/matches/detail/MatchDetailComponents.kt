package com.jobradar.app.ui.matches.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobradar.app.R
import com.jobradar.app.domain.model.MatchResult
import com.jobradar.app.domain.model.ScoreBreakdown
import com.jobradar.app.ui.matches.formatJobLevelLabel

private const val MAX_SCORE_FLOAT = 100f

@Composable
fun MatchDetailHeaderCard(
    match: MatchResult,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = match.jobTitle?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.matches_unknown_role),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Business,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = match.companyName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.matches_unknown_company),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = stringResource(R.string.match_detail_score_out_of, match.matchScore),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            val metaParts = listOfNotNull(
                formatJobLevelLabel(match.jobLevel),
                match.companyLocation?.takeIf { it.isNotBlank() },
                match.companyIndustry?.takeIf { it.isNotBlank() },
                match.companySizeRange?.takeIf { it.isNotBlank() },
            )
            if (metaParts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                    )
                }
            }
        }
    }
}

@Composable
fun ScoreBreakdownCard(
    breakdown: ScoreBreakdown,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.match_detail_section_breakdown),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ScorePillarRow(
                label = stringResource(R.string.match_detail_pillar_skills),
                score = breakdown.skills,
            )
            ScorePillarRow(
                label = stringResource(R.string.match_detail_pillar_experience),
                score = breakdown.experience,
            )
            ScorePillarRow(
                label = stringResource(R.string.match_detail_pillar_projects),
                score = breakdown.projects,
            )
        }
    }
}

@Composable
private fun ScorePillarRow(
    label: String,
    score: Int,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.match_detail_score_out_of, score),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { (score / MAX_SCORE_FLOAT).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun AiReasoningCard(
    reasoning: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.match_detail_section_reasoning),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = reasoning?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.match_detail_reasoning_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
