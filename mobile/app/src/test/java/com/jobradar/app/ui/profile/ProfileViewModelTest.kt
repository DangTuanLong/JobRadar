package com.jobradar.app.ui.profile

import com.jobradar.app.R
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.core.network.NetworkErrorCodes
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.AnalysisJobStatus
import com.jobradar.app.domain.model.CandidateProfile
import com.jobradar.app.domain.model.EnrichmentSummary
import com.jobradar.app.domain.model.ExternalLinks
import com.jobradar.app.domain.model.SkillCategory
import com.jobradar.app.domain.model.SkillItem
import com.jobradar.app.domain.model.SkillLevel
import com.jobradar.app.domain.state.ActiveAnalysisStore
import com.jobradar.app.fake.FakeAnalysisRepository
import com.jobradar.app.fake.FakeProfileRepository
import com.jobradar.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var profileRepository: FakeProfileRepository
    private lateinit var analysisRepository: FakeAnalysisRepository
    private lateinit var activeAnalysisStore: ActiveAnalysisStore
    private lateinit var viewModel: ProfileViewModel

    @Before
    fun setUp() {
        profileRepository = FakeProfileRepository()
        analysisRepository = FakeAnalysisRepository()
        activeAnalysisStore = ActiveAnalysisStore()
        viewModel = ProfileViewModel(
            profileRepository = profileRepository,
            analysisRepository = analysisRepository,
            activeAnalysisStore = activeAnalysisStore,
        )
    }

    @Test
    fun `loadProfile success exposes skill counts and GitHub verified sorting`() =
        runTest(mainDispatcherRule.testDispatcher) {
            profileRepository.getProfileProvider = { AppResult.Success(sampleProfile()) }

            viewModel.loadProfile()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.contentState as ProfileContentState.Loaded
            assertEquals(3, loaded.profile.skills.size)
            assertEquals(2, loaded.profile.githubVerifiedCount)
            assertTrue(loaded.profile.sortedSkills.first().isGithubVerified)
            assertFalse(loaded.profile.sortedSkills.last().isGithubVerified)
        }

    @Test
    fun `PROFILE_NOT_FOUND transitions to Empty state rather than generic error`() =
        runTest(mainDispatcherRule.testDispatcher) {
            profileRepository.getProfileProvider = {
                AppResult.Error(code = PROFILE_NOT_FOUND_CODE, message = "Not found")
            }

            viewModel.loadProfile()
            advanceUntilIdle()

            assertEquals(ProfileContentState.Empty, viewModel.uiState.value.contentState)
        }

    @Test
    fun `syncWithCompletedJob refreshes profile when latestCompletedJobId changes`() =
        runTest(mainDispatcherRule.testDispatcher) {
            var loadCount = 0
            profileRepository.getProfileProvider = {
                loadCount++
                AppResult.Success(sampleProfile(headline = "Headline v$loadCount"))
            }

            viewModel.syncWithCompletedJob(null)
            advanceUntilIdle()
            assertEquals(
                "Headline v1",
                (viewModel.uiState.value.contentState as ProfileContentState.Loaded).profile.headline,
            )

            viewModel.syncWithCompletedJob("job-new-1")
            advanceUntilIdle()
            assertEquals(
                "Headline v2",
                (viewModel.uiState.value.contentState as ProfileContentState.Loaded).profile.headline,
            )
            assertEquals(2, profileRepository.getProfileCalls)
        }

    @Test
    fun `rerunMatching polls to COMPLETED and updates ActiveAnalysisStore`() =
        runTest(mainDispatcherRule.testDispatcher) {
            profileRepository.getProfileProvider = { AppResult.Success(sampleProfile()) }
            profileRepository.rerunMatchesProvider = {
                AppResult.Success(sampleJob(id = "job-rerun-7", status = AnalysisJobStatus.PENDING, progress = 0))
            }
            analysisRepository.observeFlowProvider = {
                flow {
                    emit(AppResult.Success(sampleJob(id = "job-rerun-7", status = AnalysisJobStatus.MATCHING, progress = 85)))
                    emit(AppResult.Success(sampleJob(id = "job-rerun-7", status = AnalysisJobStatus.COMPLETED, progress = 100)))
                }
            }
            val events = mutableListOf<ProfileEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }

            viewModel.loadProfile()
            advanceUntilIdle()

            viewModel.rerunMatching()
            advanceUntilIdle()

            val rerunState = viewModel.uiState.value.rerunState as RerunMatchingState.Completed
            assertEquals("job-rerun-7", rerunState.job.id)
            assertEquals("job-rerun-7", activeAnalysisStore.latestCompletedJobId.value)
            assertFalse(viewModel.uiState.value.suggestRerun)
            assertTrue(events.any { it is ProfileEvent.RerunCompleted })
            collector.cancel()
        }

    @Test
    fun `network error during rerun keeps loaded profile intact`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val initialProfile = sampleProfile()
            profileRepository.getProfileProvider = { AppResult.Success(initialProfile) }
            profileRepository.rerunMatchesProvider = {
                AppResult.Error(code = NetworkErrorCodes.NETWORK_ERROR, message = "Offline")
            }

            viewModel.loadProfile()
            advanceUntilIdle()

            viewModel.rerunMatching()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.contentState as ProfileContentState.Loaded
            assertEquals(initialProfile, loaded.profile)
            val failed = viewModel.uiState.value.rerunState as RerunMatchingState.Failed
            assertEquals(R.string.auth_error_network, failed.messageRes)
            assertNull(activeAnalysisStore.latestCompletedJobId.value)
        }

    private fun sampleProfile(headline: String = "Fresher Android Developer") = CandidateProfile(
        id = "prof-1",
        userId = "user-1",
        fullName = "Nguyen Van A",
        headline = headline,
        yearsOfExperience = 1.5,
        cvFileUrl = null,
        skills = listOf(
            SkillItem("PostgreSQL", SkillCategory.DATABASE, SkillLevel.INTERMEDIATE, listOf("cv")),
            SkillItem("Kotlin", SkillCategory.LANGUAGE, SkillLevel.ADVANCED, listOf("cv", "github")),
            SkillItem("Jetpack Compose", SkillCategory.FRAMEWORK, SkillLevel.INTERMEDIATE, listOf("github")),
        ),
        experiences = emptyList(),
        externalLinks = ExternalLinks(github = "https://github.com/nguyenvana"),
        enrichmentSummary = EnrichmentSummary(githubUsername = "nguyenvana"),
        profileSummary = "Android dev",
        createdAt = null,
        updatedAt = null,
    )

    private fun sampleJob(
        id: String,
        status: AnalysisJobStatus,
        progress: Int,
    ) = AnalysisJob(
        id = id,
        userId = "user-1",
        profileId = "prof-1",
        status = status,
        progressPercent = progress,
        currentStepMessage = "Step $status",
        errorCode = null,
        errorMessage = null,
        createdAt = null,
        updatedAt = null,
    )
}
