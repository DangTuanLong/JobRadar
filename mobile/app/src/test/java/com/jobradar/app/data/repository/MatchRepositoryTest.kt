package com.jobradar.app.data.repository

import com.jobradar.app.core.network.ApiResponse
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.data.cache.MatchCacheStore
import com.jobradar.app.data.remote.dto.MatchResultDto
import com.jobradar.app.data.remote.dto.ScoreBreakdownDto
import com.jobradar.app.data.remote.dto.WebEvidenceDto
import com.jobradar.app.data.remote.dto.toDomain
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
class MatchRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private lateinit var apiService: FakeJobRadarApiService
    private lateinit var cacheStore: MatchCacheStore
    private lateinit var repository: MatchRepositoryImpl

    @Before
    fun setUp() {
        apiService = FakeJobRadarApiService()
        cacheStore = MatchCacheStore()
        repository = MatchRepositoryImpl(
            apiService = apiService,
            json = json,
            cacheStore = cacheStore,
        )
    }

    @Test
    fun `getMatches parses full DTO, sorts descending, and caches when forceRefresh is false`() =
        runTest(testDispatcher) {
            apiService.getMatchesHandler = {
                Response.success(
                    ApiResponse(
                        success = true,
                        data = listOf(
                            sampleDto(id = "m-low", score = 72, skillsScore = 70),
                            sampleDto(id = "m-high", score = 91, skillsScore = 95),
                            sampleDto(id = "m-tie", score = 91, skillsScore = 82),
                        ),
                    ),
                )
            }

            val firstResult = repository.getMatches("job-1", forceRefresh = false)
            val secondResult = repository.getMatches("job-1", forceRefresh = false)
            val refreshedResult = repository.getMatches("job-1", forceRefresh = true)

            assertTrue(firstResult is AppResult.Success)
            val list = (firstResult as AppResult.Success).data
            assertEquals(listOf("m-high", "m-tie", "m-low"), list.map { it.id })
            assertEquals(95, list.first().scoreBreakdown.skills)
            assertEquals(listOf("Kotlin", "Compose"), list.first().matchedSkills)
            assertEquals(listOf("GraphQL"), list.first().missingSkills)
            assertEquals(1, list.first().webEvidence.size)
            assertEquals("momo.vn", list.first().webEvidence.first().hostDomain)
            assertTrue(list.first().webEvidence.first().isSafeHttpUrl)
            assertEquals(list, (secondResult as AppResult.Success).data)
            assertEquals(list, (refreshedResult as AppResult.Success).data)
            assertEquals(2, apiService.getMatchesCalls)
        }

    @Test
    fun `getMatchDetail hits cache, falls back to API on miss, and returns MATCH_NOT_FOUND`() =
        runTest(testDispatcher) {
            apiService.getMatchesHandler = {
                Response.success(
                    ApiResponse(
                        success = true,
                        data = listOf(sampleDto(id = "m-1", score = 85, skillsScore = 90)),
                    ),
                )
            }

            val detailFromNetwork = repository.getMatchDetail("job-1", "m-1")
            assertTrue(detailFromNetwork is AppResult.Success)
            assertEquals("m-1", (detailFromNetwork as AppResult.Success).data.id)
            assertEquals(1, apiService.getMatchesCalls)

            val detailFromCache = repository.getMatchDetail("job-1", "m-1")
            assertTrue(detailFromCache is AppResult.Success)
            assertEquals(1, apiService.getMatchesCalls)

            val missingDetail = repository.getMatchDetail("job-1", "unknown-match")
            assertTrue(missingDetail is AppResult.Error)
            assertEquals(MATCH_NOT_FOUND_CODE, (missingDetail as AppResult.Error).code)
        }

    @Test
    fun `getMatches preserves JOB_NOT_FOUND and clearCache removes cached entries`() =
        runTest(testDispatcher) {
            val errorJson = """{"success":false,"data":null,"error":{"code":"JOB_NOT_FOUND","message":"Not found"}}"""
            apiService.getMatchesHandler = {
                Response.error(404, errorJson.toResponseBody("application/json".toMediaType()))
            }

            val notFound = repository.getMatches("bad-job")
            assertTrue(notFound is AppResult.Error)
            assertEquals("JOB_NOT_FOUND", (notFound as AppResult.Error).code)

            apiService.getMatchesHandler = {
                Response.success(ApiResponse(true, listOf(sampleDto(id = "m-1", score = 80, skillsScore = 85))))
            }
            repository.getMatches("job-2")
            assertEquals(2, apiService.getMatchesCalls)

            repository.clearCache()
            repository.getMatches("job-2")
            assertEquals(3, apiService.getMatchesCalls)
            assertFalse(sampleDto("m-unsafe", 50, 50, url = "javascript:alert(1)").toDomain().webEvidence.first().isSafeHttpUrl)
        }

    private fun sampleDto(
        id: String,
        score: Int,
        skillsScore: Int,
        url: String = "https://www.momo.vn/careers",
    ) = MatchResultDto(
        id = id,
        jobId = "job-1",
        profileId = "prof-1",
        companyId = "comp-1",
        jobPostingId = "jp-1",
        matchScore = score,
        scoreBreakdown = ScoreBreakdownDto(skills = skillsScore, experience = 75, projects = 80),
        matchedSkills = listOf("Kotlin", "Compose"),
        missingSkills = listOf("GraphQL"),
        reasoning = "Strong Kotlin alignment",
        webEvidence = listOf(
            WebEvidenceDto(title = "MoMo Engineering", url = url, snippet = "Android stack"),
        ),
        companyName = "MoMo",
        companyWebsite = "https://momo.vn",
        companyLocation = "Ho Chi Minh City",
        jobTitle = "Android Engineer",
        jobLevel = "fresher",
    )
}
