package com.jobradar.app.ui.matches

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jobradar.app.R

private const val HIGH_SCORE_THRESHOLD = 80
private const val MEDIUM_SCORE_THRESHOLD = 60

@Composable
fun MatchScoreBadge(
    score: Int,
    modifier: Modifier = Modifier,
) {
    val (container, content) = when {
        score >= HIGH_SCORE_THRESHOLD ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer

        score >= MEDIUM_SCORE_THRESHOLD ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer

        else ->
            MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = container,
    ) {
        Text(
            text = stringResource(R.string.matches_score_badge, score),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = content,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun SkillPill(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun MatchesNoJobState(
    onNavigateToScanCv: () -> Unit,
    onNavigateToProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.TravelExplore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.matches_no_job_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.matches_no_job_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onNavigateToScanCv, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.profile_action_scan_now))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onNavigateToProfile, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.matches_action_open_profile))
        }
    }
}

@Composable
fun MatchesEmptyState(
    onNavigateToScanCv: () -> Unit,
    onNavigateToProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.TravelExplore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.matches_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.matches_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(onClick = onNavigateToProfile, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.matches_action_open_profile))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onNavigateToScanCv, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.profile_action_scan_now))
        }
    }
}

@Composable
fun MatchesErrorState(
    @StringRes messageRes: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}
