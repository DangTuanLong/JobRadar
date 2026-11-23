package com.jobradar.app.ui.profile

import com.jobradar.app.R
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.core.network.NetworkErrorCodes
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var profileRepository: FakeProfileRepository
    private lateinit var viewModel: ProfileViewModel

    @Before
    fun setUp() {
        profileRepository = FakeProfileRepository()
        viewModel = ProfileViewModel(
            profileRepository = profileRepository,
            analysisRepository = FakeAnalysisRepository(),
            activeAnalysisStore = ActiveAnalysisStore(),
        )
    }

    @Test
    fun `edit flow adds skill, removes skill, updates headline, and enables suggestRerun`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val original = sampleProfile()
            profileRepository.getProfileProvider = { AppResult.Success(original) }
            profileRepository.updateProfileProvider = { req ->
                AppResult.Success(
                    original.copy(
                        headline = req.headline,
                        skills = req.skills.orEmpty(),
                    ),
                )
            }
            val events = mutableListOf<ProfileEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }

            viewModel.loadProfile()
            advanceUntilIdle()

            viewModel.openEditMode()
            viewModel.onEditHeadlineChange("Junior Android Engineer")
            viewModel.removeSkill("PostgreSQL")
            viewModel.onNewSkillNameChange("Docker")
            viewModel.onNewSkillLevelChange(SkillLevel.ADVANCED)
            viewModel.addSkill()

            viewModel.saveProfileEdits()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.editDraft)
            assertTrue(viewModel.uiState.value.suggestRerun)
            val sent = profileRepository.lastUpdateRequest
            assertEquals("Junior Android Engineer", sent?.headline)
            assertEquals(listOf("Kotlin", "Docker"), sent?.skills?.map { it.name })
            assertEquals(listOf("cv", "github"), sent?.skills?.first()?.verifiedBy)
            val loaded = viewModel.uiState.value.contentState as ProfileContentState.Loaded
            assertEquals("Junior Android Engineer", loaded.profile.headline)
            assertTrue(events.contains(ProfileEvent.ShowMessage(R.string.profile_save_success)))
            collector.cancel()
        }

    @Test
    fun `edit validation rejects empty skill, duplicate skill, and negative years`() =
        runTest(mainDispatcherRule.testDispatcher) {
            profileRepository.getProfileProvider = { AppResult.Success(sampleProfile()) }

            viewModel.loadProfile()
            advanceUntilIdle()
            viewModel.openEditMode()

            viewModel.onNewSkillNameChange("   ")
            viewModel.addSkill()
            assertEquals(R.string.profile_error_skill_empty, viewModel.uiState.value.editDraft?.skillInputError)

            viewModel.onNewSkillNameChange("kotlin")
            viewModel.addSkill()
            assertEquals(
                R.string.profile_error_skill_duplicate,
                viewModel.uiState.value.editDraft?.skillInputError,
            )

            viewModel.onEditYearsChange("-2")
            viewModel.saveProfileEdits()
            advanceUntilIdle()
            assertEquals(R.string.profile_error_years_invalid, viewModel.uiState.value.editDraft?.yearsError)
            assertEquals(0, profileRepository.updateProfileCalls)
        }

    @Test
    fun `save network error preserves loaded profile and keeps edit draft open`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val original = sampleProfile()
            profileRepository.getProfileProvider = { AppResult.Success(original) }
            profileRepository.updateProfileProvider = {
                AppResult.Error(code = NetworkErrorCodes.NETWORK_ERROR, message = "No internet")
            }
            val events = mutableListOf<ProfileEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }

            viewModel.loadProfile()
            advanceUntilIdle()
            viewModel.openEditMode()
            viewModel.onEditHeadlineChange("Mid Android Dev")
            viewModel.saveProfileEdits()
            advanceUntilIdle()

            val loaded = viewModel.uiState.value.contentState as ProfileContentState.Loaded
            assertEquals(original, loaded.profile)
            val draft = viewModel.uiState.value.editDraft
            assertNotNull(draft)
            assertFalse(draft?.isSaving ?: true)
            assertEquals("Mid Android Dev", draft?.headline)
            assertTrue(events.contains(ProfileEvent.ShowMessage(R.string.auth_error_network)))
            collector.cancel()
        }

    private fun sampleProfile() = CandidateProfile(
        id = "prof-1",
        userId = "user-1",
        fullName = "Nguyen Van A",
        headline = "Fresher Android Developer",
        yearsOfExperience = 1.5,
        cvFileUrl = null,
        skills = listOf(
            SkillItem("Kotlin", SkillCategory.LANGUAGE, SkillLevel.ADVANCED, listOf("cv", "github")),
            SkillItem("PostgreSQL", SkillCategory.DATABASE, SkillLevel.INTERMEDIATE, listOf("cv")),
        ),
        experiences = emptyList(),
        externalLinks = ExternalLinks(
            github = "https://github.com/nguyenvana",
            other = listOf("https://blog.nguyenvana.dev"),
        ),
        enrichmentSummary = EnrichmentSummary(githubUsername = "nguyenvana"),
        profileSummary = "Android dev",
        createdAt = null,
        updatedAt = null,
    )
}
