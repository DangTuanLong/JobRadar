package com.jobradar.app.ui.matches

import com.jobradar.app.R
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.core.network.NetworkErrorCodes
import com.jobradar.app.domain.model.MatchResult
import com.jobradar.app.domain.model.MatchSortOption
import com.jobradar.app.domain.model.ScoreBreakdown
import com.jobradar.app.domain.state.ActiveAnalysisStore
import com.jobradar.app.fake.FakeMatchRepository
import com.jobradar.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MatchesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var matchRepository: FakeMatchRepository
    private lateinit var activeAnalysisStore: ActiveAnalysisStore
    private lateinit var viewModel: MatchesViewModel

    @Before
    fun setUp() {
        matchRepository = FakeMatchRepository()
        activeAnalysisStore = ActiveAnalysisStore()
        viewModel = MatchesViewModel(
            matchRepository = matchRepository,
            activeAnalysisStore = activeAnalysisStore,
        )
    }

    @Test
    fun `null jobId sets NoJob state without calling repository`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.syncWithCompletedJob(null)
            advanceUntilIdle()

            assertEquals(MatchesContentState.NoJob, viewModel.uiState.value.contentState)
            assertEquals(0, matchRepository.getMatchesCalls)
        }

    @Test
    fun `syncWithCompletedJob loads matches and filters by search, level, and sort option`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val momo = sampleMatch("m-1", "MoMo", "Android Fresher", "fresher", overall = 88, skillsScore = 76)
            val vng = sampleMatch("m-2", "VNG", "Android Intern", "intern", overall = 82, skillsScore = 95)
            val grab = sampleMatch("m-3", "Grab", "Backend Intern", "intern", overall = 75, skillsScore = 80)
            matchRepository.matchesProvider = { _, _ ->
                AppResult.Success(listOf(momo, vng, grab))
            }

            activeAnalysisStore.setLatestCompletedJobId("job-42")
            viewModel.syncWithCompletedJob(viewModel.latestCompletedJobId.value)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.contentState is MatchesContentState.Loaded)
            assertEquals(listOf("intern", "fresher"), viewModel.uiState.value.availableLevels)
            assertEquals(listOf("m-1", "m-2", "m-3"), viewModel.uiState.value.displayedMatches.map { it.id })

            viewModel.onSortOptionChange(MatchSortOption.SKILLS_SCORE)
            assertEquals(listOf("m-2", "m-3", "m-1"), viewModel.uiState.value.displayedMatches.map { it.id })

            viewModel.onLevelSelected("intern")
            assertEquals(listOf("m-2", "m-3"), viewModel.uiState.value.displayedMatches.map { it.id })

            viewModel.onSearchQueryChange("VNG")
            assertEquals(listOf("m-2"), viewModel.uiState.value.displayedMatches.map { it.id })

            viewModel.clearFilters()
            assertEquals(3, viewModel.uiState.value.displayedMatches.size)
        }

    @Test
    fun `empty API result maps to Empty state`() = runTest(mainDispatcherRule.testDispatcher) {
        matchRepository.matchesProvider = { _, _ -> AppResult.Success(emptyList()) }

        viewModel.syncWithCompletedJob("job-empty")
        advanceUntilIdle()

        assertEquals(MatchesContentState.Empty, viewModel.uiState.value.contentState)
    }

    @Test
    fun `network error shows Error state and retryLoad recovers cleanly`() =
        runTest(mainDispatcherRule.testDispatcher) {
            var attempt = 0
            matchRepository.matchesProvider = { _, _ ->
                attempt++
                if (attempt == 1) {
                    AppResult.Error(NetworkErrorCodes.NETWORK_ERROR, "Offline")
                } else {
                    AppResult.Success(listOf(sampleMatch("m-1", "MoMo", "Android", "fresher", 90, 90)))
                }
            }

            viewModel.syncWithCompletedJob("job-99")
            advanceUntilIdle()

            val errState = viewModel.uiState.value.contentState as MatchesContentState.Error
            assertEquals(R.string.auth_error_network, errState.messageRes)

            viewModel.retryLoad()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.contentState is MatchesContentState.Loaded)
            assertEquals(2, matchRepository.getMatchesCalls)
        }

    private fun sampleMatch(
        id: String,
        company: String,
        role: String,
        level: String,
        overall: Int,
        skillsScore: Int,
    ) = MatchResult(
        id = id,
        jobId = "job-42",
        profileId = "prof-1",
        companyId = "comp-$id",
        jobPostingId = "jp-$id",
        companyName = company,
        companyWebsite = "https://example.com",
        companyLocation = "HCMC",
        companyIndustry = "Tech",
        companySizeRange = "200-500",
        jobTitle = role,
        jobLevel = level,
        jobSourceUrl = null,
        matchScore = overall,
        scoreBreakdown = ScoreBreakdown(skills = skillsScore, experience = 80, projects = 85),
        matchedSkills = listOf("Kotlin"),
        missingSkills = emptyList(),
        reasoning = "Good fit",
        webEvidence = emptyList(),
        createdAt = null,
    )
}
