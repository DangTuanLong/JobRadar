package com.jobradar.app.ui.upload

import com.jobradar.app.R
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.AnalysisJobStatus
import com.jobradar.app.domain.model.SelectedDocument
import com.jobradar.app.domain.state.ActiveAnalysisStore
import com.jobradar.app.domain.validator.DocumentFileValidator
import com.jobradar.app.fake.FakeAnalysisRepository
import com.jobradar.app.fake.FakeDocumentFileInspector
import com.jobradar.app.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadCvViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fileInspector: FakeDocumentFileInspector
    private lateinit var analysisRepository: FakeAnalysisRepository
    private lateinit var activeAnalysisStore: ActiveAnalysisStore
    private lateinit var viewModel: UploadCvViewModel

    @Before
    fun setUp() {
        fileInspector = FakeDocumentFileInspector()
        analysisRepository = FakeAnalysisRepository()
        activeAnalysisStore = ActiveAnalysisStore()
        viewModel = UploadCvViewModel(
            documentFileInspector = fileInspector,
            analysisRepository = analysisRepository,
            activeAnalysisStore = activeAnalysisStore,
        )
    }

    @Test
    fun `happy path updates stages to COMPLETED and sets latestCompletedJobId`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val cvDoc = SelectedDocument("content://cv.pdf", "cv.pdf", 50_000L, DocumentFileValidator.MIME_PDF)
            fileInspector.stub(cvDoc.uriString, AppResult.Success(cvDoc))
            analysisRepository.analyzeResultProvider = {
                AppResult.Success(domainJob(id = "job-99", status = AnalysisJobStatus.PENDING, progress = 0))
            }
            analysisRepository.observeFlowProvider = {
                flow {
                    delay(2_500L)
                    emit(AppResult.Success(domainJob(id = "job-99", status = AnalysisJobStatus.EXTRACTING, progress = 20)))
                    delay(2_500L)
                    emit(AppResult.Success(domainJob(id = "job-99", status = AnalysisJobStatus.ENRICHING, progress = 50)))
                    delay(2_500L)
                    emit(AppResult.Success(domainJob(id = "job-99", status = AnalysisJobStatus.MATCHING, progress = 85)))
                    delay(2_500L)
                    emit(AppResult.Success(domainJob(id = "job-99", status = AnalysisJobStatus.COMPLETED, progress = 100)))
                }
            }

            viewModel.onCvFileSelected(cvDoc.uriString)
            advanceUntilIdle()

            viewModel.startAnalysis()
            runCurrent()
            assertEquals(
                AnalysisJobStatus.PENDING,
                (viewModel.uiState.value.phase as UploadCvPhase.Tracking).job.status,
            )

            advanceTimeBy(2_500L)
            runCurrent()
            assertEquals(
                AnalysisJobStatus.EXTRACTING,
                (viewModel.uiState.value.phase as UploadCvPhase.Tracking).job.status,
            )
            assertTrue(viewModel.uiState.value.elapsedSeconds >= 2)

            advanceUntilIdle()
            val completedPhase = viewModel.uiState.value.phase as UploadCvPhase.Completed
            assertEquals("job-99", completedPhase.job.id)
            assertEquals("job-99", activeAnalysisStore.latestCompletedJobId.value)
            assertFalse(viewModel.uiState.value.isProcessing)
        }

    @Test
    fun `failed job shows mapped error then retry succeeds and reset clears state`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val cvDoc = SelectedDocument("content://cv.pdf", "cv.pdf", 50_000L, DocumentFileValidator.MIME_PDF)
            fileInspector.stub(cvDoc.uriString, AppResult.Success(cvDoc))
            var attempt = 0
            analysisRepository.analyzeResultProvider = {
                attempt++
                AppResult.Success(domainJob(id = "job-$attempt", status = AnalysisJobStatus.PENDING, progress = 0))
            }
            analysisRepository.observeFlowProvider = { jobId ->
                flow {
                    if (jobId == "job-1") {
                        emit(
                            AppResult.Success(
                                domainJob(
                                    id = "job-1",
                                    status = AnalysisJobStatus.FAILED,
                                    progress = 20,
                                    errorCode = "CV_PARSE_FAILED",
                                    errorMessage = "Empty extraction",
                                ),
                            ),
                        )
                    } else {
                        emit(
                            AppResult.Success(
                                domainJob(id = "job-2", status = AnalysisJobStatus.COMPLETED, progress = 100),
                            ),
                        )
                    }
                }
            }

            viewModel.onCvFileSelected(cvDoc.uriString)
            advanceUntilIdle()
            viewModel.startAnalysis()
            advanceUntilIdle()

            val failed = viewModel.uiState.value.phase as UploadCvPhase.Failed
            assertEquals(R.string.upload_error_cv_parse_failed, failed.messageRes)
            assertNull(activeAnalysisStore.latestCompletedJobId.value)

            viewModel.retryAnalysis()
            advanceUntilIdle()
            val completed = viewModel.uiState.value.phase as UploadCvPhase.Completed
            assertEquals("job-2", completed.job.id)
            assertEquals("job-2", activeAnalysisStore.latestCompletedJobId.value)

            viewModel.resetForNewScan()
            assertEquals(UploadCvUiState(), viewModel.uiState.value)
        }

    @Test
    fun `cancelTracking stops active polling and resets phase to Idle`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val cvDoc = SelectedDocument("content://cv.pdf", "cv.pdf", 50_000L, DocumentFileValidator.MIME_PDF)
            fileInspector.stub(cvDoc.uriString, AppResult.Success(cvDoc))
            val uploadGate = CompletableDeferred<AppResult<AnalysisJob>>()
            analysisRepository.analyzeResultProvider = { uploadGate.await() }

            viewModel.onCvFileSelected(cvDoc.uriString)
            advanceUntilIdle()
            viewModel.startAnalysis()
            runCurrent()
            assertTrue(viewModel.uiState.value.isProcessing)

            viewModel.onClearCvFile()
            assertEquals(cvDoc, viewModel.uiState.value.selectedCv)

            viewModel.cancelTracking()
            advanceUntilIdle()
            assertEquals(UploadCvPhase.Idle, viewModel.uiState.value.phase)
            assertEquals(0, viewModel.uiState.value.elapsedSeconds)
            assertFalse(viewModel.uiState.value.isProcessing)
        }

    private fun domainJob(
        id: String,
        status: AnalysisJobStatus,
        progress: Int,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) = AnalysisJob(
        id = id,
        userId = "user-1",
        profileId = null,
        status = status,
        progressPercent = progress,
        currentStepMessage = "Step $status",
        errorCode = errorCode,
        errorMessage = errorMessage,
        createdAt = null,
        updatedAt = null,
    )
}
