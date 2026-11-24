package com.jobradar.app.ui.matches.detail

import androidx.lifecycle.SavedStateHandle
import com.jobradar.app.R
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.data.repository.MATCH_NOT_FOUND_CODE
import com.jobradar.app.domain.model.MatchResult
import com.jobradar.app.domain.model.ScoreBreakdown
import com.jobradar.app.domain.model.WebEvidence
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
class MatchDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var matchRepository: FakeMatchRepository

    @Before
    fun setUp() {
        matchRepository = FakeMatchRepository()
    }

    @Test
    fun `loadDetail reads MatchDetailRoute arguments and exposes Loaded state`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val expected = sampleMatch(id = "match-7", jobId = "job-11")
            matchRepository.matchDetailProvider = { jobId, matchId ->
                assertEquals("job-11", jobId)
                assertEquals("match-7", matchId)
                AppResult.Success(expected)
            }
            val viewModel = MatchDetailViewModel(
                savedStateHandle = SavedStateHandle(mapOf("jobId" to "job-11", "matchId" to "match-7")),
                matchRepository = matchRepository,
            )

            viewModel.loadDetail()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as MatchDetailUiState.Loaded
            assertEquals(expected, loaded.match)
        }

    @Test
    fun `unknown matchId sets Error state with matches_error_not_found`() =
        runTest(mainDispatcherRule.testDispatcher) {
            matchRepository.matchDetailProvider = { _, _ ->
                AppResult.Error(code = MATCH_NOT_FOUND_CODE, message = "Not found")
            }
            val viewModel = MatchDetailViewModel(
                savedStateHandle = SavedStateHandle(mapOf("jobId" to "job-11", "matchId" to "missing")),
                matchRepository = matchRepository,
            )

            viewModel.loadDetail()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value is MatchDetailUiState.Error)
            assertEquals(
                R.string.matches_error_not_found,
                (viewModel.uiState.value as MatchDetailUiState.Error).messageRes,
            )
        }

    private fun sampleMatch(id: String, jobId: String) = MatchResult(
        id = id,
        jobId = jobId,
        profileId = "prof-1",
        companyId = "comp-1",
        jobPostingId = "jp-1",
        companyName = "MoMo",
        companyWebsite = "https://momo.vn",
        companyLocation = "HCMC",
        companyIndustry = "Fintech",
        companySizeRange = "1000+",
        jobTitle = "Android Developer",
        jobLevel = "fresher",
        jobSourceUrl = null,
        matchScore = 88,
        scoreBreakdown = ScoreBreakdown(skills = 92, experience = 80, projects = 88),
        matchedSkills = listOf("Kotlin", "Jetpack Compose"),
        missingSkills = listOf("Ktor"),
        reasoning = "Great match",
        webEvidence = listOf(WebEvidence("Tech Blog", "https://momo.vn/tech", "Kotlin stack")),
        createdAt = null,
    )
}
