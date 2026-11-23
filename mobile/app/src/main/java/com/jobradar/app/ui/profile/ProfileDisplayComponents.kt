package com.jobradar.app.ui.profile

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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jobradar.app.R
import com.jobradar.app.domain.model.CandidateProfile

@Composable
fun ProfileHeaderCard(
    profile: CandidateProfile,
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
            Text(
                text = profile.fullName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.profile_unnamed),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = profile.headline?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.profile_no_headline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
            Spacer(modifier = Modifier.height(10.dp))
            val yearsLabel = if (profile.yearsOfExperience > 0.0) {
                stringResource(R.string.profile_years_exp, formatYearsInput(profile.yearsOfExperience))
            } else {
                stringResource(R.string.profile_years_fresher)
            }
            Text(
                text = yearsLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            val githubUsername = profile.enrichmentSummary.githubUsername
            if (!githubUsername.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Verified,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                R.string.profile_github_stats,
                                githubUsername,
                                profile.enrichmentSummary.githubPublicRepos,
                                profile.enrichmentSummary.githubTotalStars,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileSummaryAndLinksCard(
    profile: CandidateProfile,
    onOpenUrl: (String) -> Unit,
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
                text = stringResource(R.string.profile_section_summary),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = profile.profileSummary?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.profile_summary_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (profile.externalLinks.hasAnyLink) {
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    profile.externalLinks.github?.takeIf { it.isNotBlank() }?.let { url ->
                        ExternalLinkChip(
                            label = stringResource(R.string.profile_link_github),
                            onClick = { onOpenUrl(url) },
                        )
                    }
                    profile.externalLinks.portfolio?.takeIf { it.isNotBlank() }?.let { url ->
                        ExternalLinkChip(
                            label = stringResource(R.string.profile_link_portfolio),
                            onClick = { onOpenUrl(url) },
                        )
                    }
                    profile.externalLinks.linkedin?.takeIf { it.isNotBlank() }?.let { url ->
                        ExternalLinkChip(
                            label = stringResource(R.string.profile_link_linkedin),
                            onClick = { onOpenUrl(url) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExternalLinkChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = { Text(text = label) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
        },
    )
}
