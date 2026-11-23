package com.jobradar.app.ui.profile

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobradar.app.R
import com.jobradar.app.domain.model.CandidateProfile
import com.jobradar.app.domain.model.EnrichmentSummary
import com.jobradar.app.domain.model.ExperienceItem
import com.jobradar.app.domain.model.ExperienceType
import com.jobradar.app.domain.model.ExternalLinks
import com.jobradar.app.domain.model.SkillCategory
import com.jobradar.app.domain.model.SkillItem
import com.jobradar.app.domain.model.SkillLevel
import com.jobradar.app.ui.theme.JobRadarTheme
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("ProfileScreen")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    isSigningOut: Boolean,
    onSignOut: () -> Unit,
    onNavigateToScanCv: () -> Unit,
    onNavigateToSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val latestCompletedJobId by viewModel.latestCompletedJobId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val latestOnNavigateToSuggestions by rememberUpdatedState(onNavigateToSuggestions)

    LaunchedEffect(latestCompletedJobId) {
        viewModel.syncWithCompletedJob(latestCompletedJobId)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes))
                }

                is ProfileEvent.RerunCompleted -> {
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(event.messageRes),
                        actionLabel = resources.getString(R.string.profile_rerun_action_view),
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        latestOnNavigateToSuggestions()
                    }
                }
            }
        }
    }

    val openExternalUrl: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (e: IllegalArgumentException) {
            logger.log(Level.WARNING, "Invalid external URL", e)
            coroutineScope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.profile_error_open_link)) }
        } catch (e: ActivityNotFoundException) {
            logger.log(Level.WARNING, "No browser available for external URL", e)
            coroutineScope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.profile_error_open_link)) }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            ProfileTopBar(
                canEdit = uiState.contentState is ProfileContentState.Loaded,
                isRefreshing = uiState.isRefreshing,
                isSigningOut = isSigningOut,
                onEditClick = viewModel::openEditMode,
                onRefreshClick = viewModel::refreshProfile,
                onSignOutClick = onSignOut,
            )
        },
    ) { innerPadding ->
        ProfileContent(
            uiState = uiState,
            onOpenUrl = openExternalUrl,
            onRetryLoad = viewModel::refreshProfile,
            onScanCvClick = onNavigateToScanCv,
            onRerunClick = viewModel::rerunMatching,
            onCancelRerunClick = viewModel::cancelRerunTracking,
            onViewSuggestionsClick = onNavigateToSuggestions,
            modifier = Modifier.padding(innerPadding),
        )
    }

    uiState.editDraft?.let { draft ->
        ProfileEditSheet(
            draft = draft,
            onDismiss = viewModel::dismissEditMode,
            onFullNameChange = viewModel::onEditFullNameChange,
            onHeadlineChange = viewModel::onEditHeadlineChange,
            onYearsChange = viewModel::onEditYearsChange,
            onSummaryChange = viewModel::onEditSummaryChange,
            onGithubChange = viewModel::onEditGithubUrlChange,
            onPortfolioChange = viewModel::onEditPortfolioUrlChange,
            onLinkedinChange = viewModel::onEditLinkedinUrlChange,
            onNewSkillNameChange = viewModel::onNewSkillNameChange,
            onNewSkillLevelChange = viewModel::onNewSkillLevelChange,
            onAddSkill = viewModel::addSkill,
            onRemoveSkill = viewModel::removeSkill,
            onSave = viewModel::saveProfileEdits,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileTopBar(
    canEdit: Boolean,
    isRefreshing: Boolean,
    isSigningOut: Boolean,
    onEditClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onSignOutClick: () -> Unit,
) {
    TopAppBar(
        title = { Text(text = stringResource(R.string.profile_title)) },
        actions = {
            if (canEdit) {
                IconButton(onClick = onEditClick) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.profile_action_edit))
                }
            }
            IconButton(onClick = onRefreshClick, enabled = !isRefreshing) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.profile_action_refresh))
            }
            IconButton(onClick = onSignOutClick, enabled = !isSigningOut) {
                if (isSigningOut) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = stringResource(R.string.action_sign_out),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@Composable
fun ProfileContent(
    uiState: ProfileUiState,
    onOpenUrl: (String) -> Unit,
    onRetryLoad: () -> Unit,
    onScanCvClick: () -> Unit,
    onRerunClick: () -> Unit,
    onCancelRerunClick: () -> Unit,
    onViewSuggestionsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val content = uiState.contentState) {
        ProfileContentState.Loading -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        ProfileContentState.Empty -> ProfileEmptyStateCard(onScanCvClick = onScanCvClick, modifier = modifier)
        is ProfileContentState.Error -> ProfileErrorStateCard(
            messageRes = content.messageRes,
            onRetryClick = onRetryLoad,
            modifier = modifier,
        )

        is ProfileContentState.Loaded -> {
            val scrollState = rememberScrollState()
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (uiState.isRefreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                ProfileHeaderCard(profile = content.profile)
                ProfileSummaryAndLinksCard(profile = content.profile, onOpenUrl = onOpenUrl)
                ProfileSkillsCard(profile = content.profile)
                ProfileExperiencesCard(experiences = content.profile.experiences)
                ProfileRerunCard(
                    rerunState = uiState.rerunState,
                    suggestRerun = uiState.suggestRerun,
                    onRerunClick = onRerunClick,
                    onCancelRerunClick = onCancelRerunClick,
                    onViewSuggestionsClick = onViewSuggestionsClick,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProfileLoadedPreview() {
    val sample = CandidateProfile(
        id = "prof-1",
        userId = "user-1",
        fullName = "Nguyễn Văn A",
        headline = "Fresher Android Developer (Kotlin & Compose)",
        yearsOfExperience = 1.5,
        cvFileUrl = null,
        skills = listOf(
            SkillItem("Kotlin", SkillCategory.LANGUAGE, SkillLevel.ADVANCED, listOf("cv", "github")),
            SkillItem("Jetpack Compose", SkillCategory.FRAMEWORK, SkillLevel.INTERMEDIATE, listOf("cv", "github")),
            SkillItem("PostgreSQL", SkillCategory.DATABASE, SkillLevel.INTERMEDIATE, listOf("cv")),
        ),
        experiences = listOf(
            ExperienceItem("JobRadar App", "Khóa luận", ExperienceType.PROJECT, "2026", "AI matching app", listOf("Kotlin")),
        ),
        externalLinks = ExternalLinks(github = "https://github.com/nguyenvana", portfolio = "https://nguyenvana.dev"),
        enrichmentSummary = EnrichmentSummary("nguyenvana", githubPublicRepos = 14, githubTotalStars = 32),
        profileSummary = "Lập trình viên Android định hướng Clean Architecture.",
        createdAt = null,
        updatedAt = null,
    )
    JobRadarTheme {
        ProfileContent(
            uiState = ProfileUiState(contentState = ProfileContentState.Loaded(sample), suggestRerun = true),
            onOpenUrl = {}, onRetryLoad = {}, onScanCvClick = {},
            onRerunClick = {}, onCancelRerunClick = {}, onViewSuggestionsClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProfileEmptyPreview() {
    JobRadarTheme {
        ProfileContent(
            uiState = ProfileUiState(contentState = ProfileContentState.Empty),
            onOpenUrl = {}, onRetryLoad = {}, onScanCvClick = {},
            onRerunClick = {}, onCancelRerunClick = {}, onViewSuggestionsClick = {},
        )
    }
}
