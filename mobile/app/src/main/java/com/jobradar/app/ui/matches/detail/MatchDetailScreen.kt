package com.jobradar.app.ui.matches.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobradar.app.R
import com.jobradar.app.domain.model.MatchResult
import com.jobradar.app.domain.model.ScoreBreakdown
import com.jobradar.app.domain.model.WebEvidence
import com.jobradar.app.domain.model.isSafeWebUrl
import com.jobradar.app.ui.matches.MatchesErrorState
import com.jobradar.app.ui.theme.JobRadarTheme
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("MatchDetailScreen")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDetailScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MatchDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.loadDetail()
    }

    val openSafeUrl: (String) -> Unit = { rawUrl ->
        val safeUrl = rawUrl.trim()
        if (isSafeWebUrl(safeUrl)) {
            runCatching { uriHandler.openUri(safeUrl) }.onFailure { err ->
                logger.log(Level.WARNING, "Unable to open URL in browser", err)
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(resources.getString(R.string.profile_error_open_link))
                }
            }
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.profile_error_open_link))
            }
        }
    }

    val loadedMatch = (uiState as? MatchDetailUiState.Loaded)?.match
    val titleText = loadedMatch?.companyName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.match_detail_title)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.auth_back_cd),
                        )
                    }
                },
                actions = {
                    val website = loadedMatch?.companyWebsite
                    if (loadedMatch?.hasSafeCompanyWebsite == true && website != null) {
                        IconButton(onClick = { openSafeUrl(website) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = stringResource(R.string.match_detail_open_website),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        MatchDetailContent(
            uiState = uiState,
            onOpenUrl = openSafeUrl,
            onRetry = viewModel::loadDetail,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
fun MatchDetailContent(
    uiState: MatchDetailUiState,
    onOpenUrl: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        MatchDetailUiState.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is MatchDetailUiState.Error -> MatchesErrorState(
            messageRes = uiState.messageRes,
            onRetry = onRetry,
            modifier = modifier,
        )

        is MatchDetailUiState.Loaded -> {
            val scrollState = rememberScrollState()
            val match = uiState.match
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MatchDetailHeaderCard(match = match)
                ScoreBreakdownCard(breakdown = match.scoreBreakdown)
                AiReasoningCard(reasoning = match.reasoning)
                SkillGapCard(
                    matchedSkills = match.matchedSkills,
                    missingSkills = match.missingSkills,
                )
                WebEvidenceCard(
                    evidence = match.webEvidence,
                    onOpenUrl = onOpenUrl,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MatchDetailLoadedPreview() {
    val sample = MatchResult(
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
        jobTitle = "Fresher Android Developer (Kotlin)",
        jobLevel = "fresher",
        jobSourceUrl = null,
        matchScore = 88,
        scoreBreakdown = ScoreBreakdown(skills = 92, experience = 80, projects = 88),
        matchedSkills = listOf("Kotlin", "Jetpack Compose", "Coroutines", "Hilt"),
        missingSkills = listOf("Ktor Client", "CI/CD Fastlane"),
        reasoning = "Hồ sơ của bạn đáp ứng tốt nền tảng Kotlin và Jetpack Compose, đồng thời dự án GitHub chứng minh kinh nghiệm thực tế.",
        webEvidence = listOf(
            WebEvidence(
                title = "MoMo Engineering — Mobile Tech Stack 2026",
                url = "https://momo.vn/tech-blog",
                snippet = "Ứng dụng Android sử dụng Kotlin, Jetpack Compose và Clean Architecture cho các luồng thanh toán.",
            ),
        ),
        createdAt = null,
    )
    JobRadarTheme {
        MatchDetailContent(
            uiState = MatchDetailUiState.Loaded(sample),
            onOpenUrl = {},
            onRetry = {},
        )
    }
}
