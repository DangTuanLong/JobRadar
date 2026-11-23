package com.jobradar.app.ui.profile

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.jobradar.app.domain.model.CandidateProfile
import com.jobradar.app.domain.model.ExperienceItem
import com.jobradar.app.domain.model.ExperienceType
import com.jobradar.app.domain.model.SkillItem

@Composable
fun ProfileSkillsCard(
    profile: CandidateProfile,
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
            Text(
                text = stringResource(R.string.profile_section_skills),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.profile_skills_summary,
                    profile.skills.size,
                    profile.githubVerifiedCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (profile.skills.isEmpty()) {
                Text(
                    text = stringResource(R.string.profile_skills_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    profile.sortedSkills.forEach { skill ->
                        SkillBadgeChip(skill = skill)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillBadgeChip(
    skill: SkillItem,
    modifier: Modifier = Modifier,
) {
    val isVerified = skill.isGithubVerified
    val containerColor = if (isVerified) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isVerified) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val border = if (isVerified) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isVerified) {
                Icon(
                    imageVector = Icons.Outlined.Verified,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = skill.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isVerified) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
            )
            if (isVerified) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                ) {
                    Text(
                        text = stringResource(R.string.profile_badge_github),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileExperiencesCard(
    experiences: List<ExperienceItem>,
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
            Text(
                text = stringResource(R.string.profile_section_experiences),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (experiences.isEmpty()) {
                Text(
                    text = stringResource(R.string.profile_experiences_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                experiences.forEachIndexed { index, item ->
                    ExperienceRow(item = item)
                    if (index < experiences.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExperienceRow(
    item: ExperienceItem,
    modifier: Modifier = Modifier,
) {
    val icon = when (item.itemType) {
        ExperienceType.WORK -> Icons.Outlined.WorkOutline
        ExperienceType.PROJECT -> Icons.Outlined.Code
        ExperienceType.EDUCATION -> Icons.Outlined.School
        ExperienceType.OTHER -> Icons.Outlined.WorkOutline
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = stringResource(experienceTypeLabelRes(item.itemType)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        val subtitleParts = listOfNotNull(
            item.organization?.takeIf { it.isNotBlank() },
            item.duration?.takeIf { it.isNotBlank() },
        )
        if (subtitleParts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitleParts.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!item.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (item.technologies.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.technologies.joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
