package com.jobradar.app.data.repository

import com.jobradar.app.core.network.ApiResponse
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.data.remote.dto.AnalysisJobDto
import com.jobradar.app.data.remote.dto.CandidateProfileDto
import com.jobradar.app.data.remote.dto.EnrichmentSummaryDto
import com.jobradar.app.data.remote.dto.ExperienceItemDto
import com.jobradar.app.data.remote.dto.ExternalLinksDto
import com.jobradar.app.data.remote.dto.SkillItemDto
import com.jobradar.app.domain.model.AnalysisJobStatus
import com.jobradar.app.domain.model.CandidateProfileUpdate
import com.jobradar.app.domain.model.ExternalLinks
import com.jobradar.app.domain.model.SkillCategory
import com.jobradar.app.domain.model.SkillItem
import com.jobradar.app.domain.model.SkillLevel
import com.jobradar.app.fake.FakeJobRadarApiService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private lateinit var apiService: FakeJobRadarApiService
    private lateinit var repository: ProfileRepositoryImpl

    @Before
    fun setUp() {
        apiService = FakeJobRadarApiService()
        repository = ProfileRepositoryImpl(apiService = apiService, json = json)
    }

    @Test
    fun `getMyProfile maps verified_by github to isGithubVerified accurately`() =
        runTest(testDispatcher) {
            apiService.getProfileHandler = {
                Response.success(
                    ApiResponse(
                        success = true,
                        data = sampleProfileDto(),
                    ),
                )
            }

            val result = repository.getMyProfile()

            assertTrue(result is AppResult.Success)
            val profile = (result as AppResult.Success).data
            assertEquals("prof-1", profile.id)
            assertEquals(2, profile.skills.size)
            assertEquals(1, profile.githubVerifiedCount)
            val kotlinSkill = profile.skills.first { it.name == "Kotlin" }
            val sqlSkill = profile.skills.first { it.name == "PostgreSQL" }
            assertTrue(kotlinSkill.isGithubVerified)
            assertFalse(sqlSkill.isGithubVerified)
            assertEquals("nguyenvana", profile.enrichmentSummary.githubUsername)
        }

    @Test
    fun `getMyProfile preserves PROFILE_NOT_FOUND code on HTTP 404`() =
        runTest(testDispatcher) {
            val errorJson = """{"success":false,"data":null,"error":{"code":"PROFILE_NOT_FOUND","message":"Not found"}}"""
            apiService.getProfileHandler = {
                Response.error(404, errorJson.toResponseBody("application/json".toMediaType()))
            }

            val result = repository.getMyProfile()

            assertTrue(result is AppResult.Error)
            assertEquals("PROFILE_NOT_FOUND", (result as AppResult.Error).code)
        }

    @Test
    fun `updateMyProfile sends expected DTO body and maps updated response`() =
        runTest(testDispatcher) {
            val updatedDto = sampleProfileDto(
                headline = "Junior Android Engineer",
                skills = listOf(
                    SkillItemDto("Kotlin", "language", "advanced", listOf("cv", "github")),
                    SkillItemDto("Docker", "cloud_devops", "intermediate", emptyList()),
                ),
            )
            apiService.updateProfileHandler = {
                Response.success(ApiResponse(success = true, data = updatedDto))
            }

            val update = CandidateProfileUpdate(
                fullName = "Nguyen Van A",
                headline = "Junior Android Engineer",
                yearsOfExperience = 2.0,
                skills = listOf(
                    SkillItem("Kotlin", SkillCategory.LANGUAGE, SkillLevel.ADVANCED, listOf("cv", "github")),
                    SkillItem("Docker", SkillCategory.CLOUD_DEVOPS, SkillLevel.INTERMEDIATE, emptyList()),
                ),
                externalLinks = ExternalLinks(
                    github = "https://github.com/nguyenvana",
                    portfolio = "https://nguyenvana.dev",
                    other = listOf("https://blog.nguyenvana.dev"),
                ),
                profileSummary = "Updated summary",
            )

            val result = repository.updateMyProfile(update)

            assertTrue(result is AppResult.Success)
            val sent = apiService.lastProfileUpdateRequest
            assertEquals("Junior Android Engineer", sent?.headline)
            assertEquals(2.0, sent?.yearsOfExperience)
            assertEquals(listOf("cv", "github"), sent?.skills?.first()?.verifiedBy)
            assertEquals(listOf("https://blog.nguyenvana.dev"), sent?.externalLinks?.other)
            assertEquals("Junior Android Engineer", (result as AppResult.Success).data.headline)
        }

    @Test
    fun `rerunMatches handles HTTP 202 Accepted and returns AnalysisJob`() =
        runTest(testDispatcher) {
            apiService.rerunMatchesHandler = {
                Response.success(
                    202,
                    ApiResponse(
                        success = true,
                        data = AnalysisJobDto(
                            id = "job-rerun-202",
                            userId = "user-1",
                            profileId = "prof-1",
                            status = "PENDING",
                            progressPercent = 0,
                        ),
                    ),
                )
            }

            val result = repository.rerunMatches()

            assertTrue(result is AppResult.Success)
            val job = (result as AppResult.Success).data
            assertEquals("job-rerun-202", job.id)
            assertEquals(AnalysisJobStatus.PENDING, job.status)
            assertEquals("prof-1", job.profileId)
        }

    private fun sampleProfileDto(
        headline: String = "Fresher Android Developer",
        skills: List<SkillItemDto> = listOf(
            SkillItemDto("Kotlin", "language", "advanced", listOf("cv", "GITHUB")),
            SkillItemDto("PostgreSQL", "database", "intermediate", listOf("cv")),
        ),
    ) = CandidateProfileDto(
        id = "prof-1",
        userId = "user-1",
        fullName = "Nguyen Van A",
        headline = headline,
        yearsOfExperience = 1.5,
        skills = skills,
        experiences = listOf(
            ExperienceItemDto(
                title = "JobRadar App",
                organization = "Thesis",
                itemType = "project",
                duration = "2026",
                technologies = listOf("Kotlin", "FastAPI"),
            ),
        ),
        externalLinks = ExternalLinksDto(
            github = "https://github.com/nguyenvana",
            portfolio = "https://nguyenvana.dev",
        ),
        enrichmentSummary = EnrichmentSummaryDto(
            githubUsername = "nguyenvana",
            githubPublicRepos = 12,
            githubTotalStars = 24,
        ),
        profileSummary = "Android engineer",
    )
}
