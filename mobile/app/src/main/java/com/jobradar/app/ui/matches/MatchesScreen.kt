package com.jobradar.app.ui.matches

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobradar.app.R
import com.jobradar.app.domain.model.MatchResult
import com.jobradar.app.domain.model.MatchSortOption
import com.jobradar.app.domain.model.ScoreBreakdown
import com.jobradar.app.domain.model.WebEvidence
import com.jobradar.app.ui.theme.JobRadarTheme

private const val MATCH_CARD_CONTENT_TYPE = "match_card"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchesScreen(
    onOpenMatchDetail: (jobId: String, matchId: String) -> Unit,
    onNavigateToScanCv: () -> Unit,
    onNavigateToProfile: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MatchesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val latestCompletedJobId by viewModel.latestCompletedJobId.collectAsStateWithLifecycle()

    LaunchedEffect(latestCompletedJobId) {
        viewModel.syncWithCompletedJob(latestCompletedJobId)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.matches_title)) },
                actions = {
                    if (uiState.activeJobId != null) {
                        IconButton(
                            onClick = viewModel::refreshMatches,
                            enabled = !uiState.isRefreshing,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.profile_action_refresh),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        MatchesContent(
            uiState = uiState,
            onSearchQueryChange = viewModel::onSearchQueryChange,
            onLevelSelected = viewModel::onLevelSelected,
            onSortOptionChange = viewModel::onSortOptionChange,
            onClearFilters = viewModel::clearFilters,
            onRetry = viewModel::retryLoad,
            onOpenMatchDetail = onOpenMatchDetail,
            onNavigateToScanCv = onNavigateToScanCv,
            onNavigateToProfile = onNavigateToProfile,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
fun MatchesContent(
    uiState: MatchesUiState,
    onSearchQueryChange: (String) -> Unit,
    onLevelSelected: (String?) -> Unit,
    onSortOptionChange: (MatchSortOption) -> Unit,
    onClearFilters: () -> Unit,
    onRetry: () -> Unit,
    onOpenMatchDetail: (jobId: String, matchId: String) -> Unit,
    onNavigateToScanCv: () -> Unit,
    onNavigateToProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val content = uiState.contentState) {
        MatchesContentState.NoJob -> MatchesNoJobState(
            onNavigateToScanCv = onNavigateToScanCv,
            onNavigateToProfile = onNavigateToProfile,
            modifier = modifier,
        )

        MatchesContentState.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        MatchesContentState.Empty -> MatchesEmptyState(
            onNavigateToScanCv = onNavigateToScanCv,
            onNavigateToProfile = onNavigateToProfile,
            modifier = modifier,
        )

        is MatchesContentState.Error -> MatchesErrorState(
            messageRes = content.messageRes,
            onRetry = onRetry,
            modifier = modifier,
        )

        is MatchesContentState.Loaded -> {
            val displayed = uiState.displayedMatches
            Column(modifier = modifier.fillMaxSize()) {
                if (uiState.isRefreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                MatchesFilterHeader(
                    searchQuery = uiState.searchQuery,
                    availableLevels = uiState.availableLevels,
                    selectedLevel = uiState.selectedLevel,
                    sortOption = uiState.sortOption,
                    resultCount = displayed.size,
                    onSearchQueryChange = onSearchQueryChange,
                    onLevelSelected = onLevelSelected,
                    onSortOptionChange = onSortOptionChange,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (displayed.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.matches_filter_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (uiState.hasActiveFilters) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(onClick = onClearFilters) {
                                Text(text = stringResource(R.string.matches_action_clear_filters))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(
                            items = displayed,
                            key = { _, item -> item.id },
                            contentType = { _, _ -> MATCH_CARD_CONTENT_TYPE },
                        ) { index, match ->
                            MatchResultCard(
                                rank = index + 1,
                                match = match,
                                onClick = { onOpenMatchDetail(match.jobId, match.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MatchesLoadedPreview() {
    val sampleMatches = listOf(
        MatchResult(
            id = "m-1",
            jobId = "job-1",
            profileId = "prof-1",
            companyId = "comp-1",
            jobPostingId = "jp-1",
            companyName = "MoMo (M_Service)",
            companyWebsite = "https://momo.vn",
            companyLocation = "TP. Hồ Chí Minh",
            companyIndustry = "Fintech",
            companySizeRange = "1000+",
            jobTitle = "Fresher Android Engineer (Kotlin)",
            jobLevel = "fresher",
            jobSourceUrl = null,
            matchScore = 88,
            scoreBreakdown = ScoreBreakdown(skills = 92, experience = 80, projects = 88),
            matchedSkills = listOf("Kotlin", "Jetpack Compose", "Coroutines", "Hilt"),
            missingSkills = listOf("Unit Testing Espresso"),
            reasoning = "Kinh nghiệm Android và dự án thực tế trên GitHub rất phù hợp.",
            webEvidence = listOf(
                WebEvidence("MoMo Tech Blog", "https://momo.vn/tech", "Android Kotlin architecture"),
            ),
            createdAt = null,
        ),
    )
    JobRadarTheme {
        MatchesContent(
            uiState = MatchesUiState(
                contentState = MatchesContentState.Loaded(sampleMatches),
                activeJobId = "job-1",
            ),
            onSearchQueryChange = {}, onLevelSelected = {}, onSortOptionChange = {},
            onClearFilters = {}, onRetry = {}, onOpenMatchDetail = { _, _ -> },
            onNavigateToScanCv = {}, onNavigateToProfile = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MatchesNoJobPreview() {
    JobRadarTheme {
        MatchesContent(
            uiState = MatchesUiState(contentState = MatchesContentState.NoJob),
            onSearchQueryChange = {}, onLevelSelected = {}, onSortOptionChange = {},
            onClearFilters = {}, onRetry = {}, onOpenMatchDetail = { _, _ -> },
            onNavigateToScanCv = {}, onNavigateToProfile = {},
        )
    }
}
