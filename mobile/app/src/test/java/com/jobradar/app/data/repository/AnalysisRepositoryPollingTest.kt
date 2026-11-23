package com.jobradar.app.data.repository

import com.jobradar.app.core.network.ApiResponse
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.core.network.NetworkConstants
import com.jobradar.app.core.network.NetworkErrorCodes
import com.jobradar.app.data.file.DocumentMultipartFactory
import com.jobradar.app.data.remote.dto.AnalysisJobDto
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.AnalysisJobStatus
import com.jobradar.app.domain.model.SelectedDocument
import com.jobradar.app.domain.state.ActiveAnalysisStore
import com.jobradar.app.domain.validator.DocumentFileValidator
import com.jobradar.app.fake.FakeJobRadarApiService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisRepositoryPollingTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private lateinit var apiService: FakeJobRadarApiService
    private lateinit var activeAnalysisStore: ActiveAnalysisStore
    private lateinit var repository: AnalysisRepositoryImpl

    @Before
    fun setUp() {
        apiService = FakeJobRadarApiService()
        activeAnalysisStore = ActiveAnalysisStore()
        val fakeMultipartFactory = DocumentMultipartFactory { doc, partName ->
            val part = MultipartBody.Part.createFormData(
                partName,
                doc.displayName,
                "sample-bytes".toRequestBody(doc.mimeType.toMediaType()),
            )
            AppResult.Success(part)
        }
        repository = AnalysisRepositoryImpl(
            apiService = apiService,
            json = json,
            multipartFactory = fakeMultipartFactory,
            activeAnalysisStore = activeAnalysisStore,
            pollingConfig = AnalysisPollingConfig(
                pollIntervalMs = 2_500L,
                maxConsecutiveTransientErrors = 3,
                maxDurationMs = 300_000L,
            ),
        )
    }

    @Test
    fun `analyzeCv handles HTTP 202 Accepted and exact multipart field names`() =
        runTest(testDispatcher) {
            apiService.analyzeHandler = {
                Response.success(
                    202,
                    ApiResponse(
                        success = true,
                        data = jobDto(id = "job-202", status = "PENDING", progress = 0),
                    ),
                )
            }
            val cv = SelectedDocument("content://cv.pdf", "cv.pdf", 1024L, DocumentFileValidator.MIME_PDF)
            val linkedin = SelectedDocument("content://li.pdf", "li.pdf", 2048L, DocumentFileValidator.MIME_PDF)

            val result = repository.analyzeCv(cv, linkedin)

            assertTrue(result is AppResult.Success)
            val job = (result as AppResult.Success).data
            assertEquals("job-202", job.id)
            assertEquals(AnalysisJobStatus.PENDING, job.status)
            assertTrue(apiService.lastCvPart?.headers?.get("Content-Disposition")?.contains("name=\"${NetworkConstants.PART_CV_FILE}\"") == true)
            assertTrue(apiService.lastLinkedInPart?.headers?.get("Content-Disposition")?.contains("name=\"${NetworkConstants.PART_LINKEDIN_PDF}\"") == true)
        }

    @Test
    fun `observeJobStatus polls through PENDING to COMPLETED and updates ActiveAnalysisStore`() =
        runTest(testDispatcher) {
            apiService.enqueuePollResponses(
                { Response.success(ApiResponse(true, jobDto(status = "PENDING", progress = 0))) },
                { Response.success(ApiResponse(true, jobDto(status = "EXTRACTING", progress = 20))) },
                { Response.success(ApiResponse(true, jobDto(status = "ENRICHING", progress = 50))) },
                { Response.success(ApiResponse(true, jobDto(status = "MATCHING", progress = 85))) },
                { Response.success(ApiResponse(true, jobDto(status = "COMPLETED", progress = 100, profileId = "prof-1"))) },
            )

            val emissions = repository.observeJobStatus("job-1").toList()

            assertEquals(5, emissions.size)
            val statuses = emissions.map { (it as AppResult.Success).data.status }
            assertEquals(
                listOf(
                    AnalysisJobStatus.PENDING,
                    AnalysisJobStatus.EXTRACTING,
                    AnalysisJobStatus.ENRICHING,
                    AnalysisJobStatus.MATCHING,
                    AnalysisJobStatus.COMPLETED,
                ),
                statuses,
            )
            assertEquals("job-1", activeAnalysisStore.latestCompletedJobId.value)
            assertEquals(5, apiService.getJobCalls)
        }

    @Test
    fun `observeJobStatus stops immediately on FAILED with error code`() =
        runTest(testDispatcher) {
            apiService.enqueuePollResponses(
                { Response.success(ApiResponse(true, jobDto(status = "EXTRACTING", progress = 20))) },
                {
                    Response.success(
                        ApiResponse(
                            true,
                            jobDto(
                                status = "FAILED",
                                progress = 20,
                                errorCode = "CV_PARSE_FAILED",
                                errorMessage = "Could not extract profile",
                            ),
                        ),
                    )
                },
            )

            val emissions = repository.observeJobStatus("job-1").toList()

            assertEquals(2, emissions.size)
            val failedJob = (emissions.last() as AppResult.Success).data
            assertEquals(AnalysisJobStatus.FAILED, failedJob.status)
            assertEquals("CV_PARSE_FAILED", failedJob.errorCode)
            assertNull(activeAnalysisStore.latestCompletedJobId.value)
            assertEquals(2, apiService.getJobCalls)
        }

    @Test
    fun `observeJobStatus recovers after two transient network errors and completes`() =
        runTest(testDispatcher) {
            apiService.enqueuePollResponses(
                { throw IOException("Temporary socket reset") },
                { throw SocketTimeoutException("Read timed out") },
                { Response.success(ApiResponse(true, jobDto(status = "COMPLETED", progress = 100))) },
            )

            val emissions = repository.observeJobStatus("job-1").toList()

            assertEquals(1, emissions.size)
            val result = emissions.single() as AppResult.Success<AnalysisJob>
            assertEquals(AnalysisJobStatus.COMPLETED, result.data.status)
            assertEquals("job-1", activeAnalysisStore.latestCompletedJobId.value)
            assertEquals(3, apiService.getJobCalls)
        }

    @Test
    fun `observeJobStatus stops and emits error after three consecutive transient failures`() =
        runTest(testDispatcher) {
            apiService.enqueuePollResponses(
                { throw IOException("Drop 1") },
                { throw SocketTimeoutException("Drop 2") },
                { throw IOException("Drop 3") },
                { Response.success(ApiResponse(true, jobDto(status = "COMPLETED", progress = 100))) },
            )

            val emissions = repository.observeJobStatus("job-1").toList()

            assertEquals(1, emissions.size)
            val error = emissions.single() as AppResult.Error
            assertEquals(NetworkErrorCodes.NETWORK_ERROR, error.code)
            assertEquals(3, apiService.getJobCalls)
            assertNull(activeAnalysisStore.latestCompletedJobId.value)
        }

    @Test
    fun `observeJobStatus stops immediately without retry on HTTP 404 JOB_NOT_FOUND`() =
        runTest(testDispatcher) {
            val errorJson = """{"success":false,"data":null,"error":{"code":"JOB_NOT_FOUND","message":"Analysis job not found."}}"""
            apiService.enqueuePollResponses(
                { Response.error(404, errorJson.toResponseBody("application/json".toMediaType())) },
            )

            val emissions = repository.observeJobStatus("missing-job").toList()

            assertEquals(1, emissions.size)
            val error = emissions.single() as AppResult.Error
            assertEquals("JOB_NOT_FOUND", error.code)
            assertEquals(1, apiService.getJobCalls)
        }

    @Test
    fun `observeJobStatus stops immediately without retry on HTTP 401 unauthorized`() =
        runTest(testDispatcher) {
            val errorJson = """{"success":false,"data":null,"error":{"code":"AUTH_INVALID_TOKEN","message":"Token is invalid or expired."}}"""
            apiService.enqueuePollResponses(
                { Response.error(401, errorJson.toResponseBody("application/json".toMediaType())) },
            )

            val emissions = repository.observeJobStatus("job-1").toList()

            assertEquals(1, emissions.size)
            val error = emissions.single() as AppResult.Error
            assertEquals("AUTH_INVALID_TOKEN", error.code)
            assertEquals(1, apiService.getJobCalls)
        }

    @Test
    fun `observeJobStatus emits POLLING_TIMEOUT when job never terminates within maxDuration`() =
        runTest(testDispatcher) {
            apiService.fallbackPollHandler = {
                Response.success(ApiResponse(true, jobDto(status = "MATCHING", progress = 85)))
            }

            val emissions = repository.observeJobStatus("job-stuck").toList()

            assertTrue(emissions.isNotEmpty())
            val last = emissions.last() as AppResult.Error
            assertEquals(NetworkErrorCodes.POLLING_TIMEOUT, last.code)
            assertNull(activeAnalysisStore.latestCompletedJobId.value)
        }

    private fun jobDto(
        id: String = "job-1",
        status: String,
        progress: Int,
        profileId: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) = AnalysisJobDto(
        id = id,
        userId = "user-1",
        profileId = profileId,
        status = status,
        progressPercent = progress,
        currentStepMessage = "Step $status",
        errorCode = errorCode,
        errorMessage = errorMessage,
    )
}
