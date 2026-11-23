package com.jobradar.app.ui.upload

import com.jobradar.app.R
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.SelectedDocument
import com.jobradar.app.domain.state.ActiveAnalysisStore
import com.jobradar.app.domain.validator.DocumentFileValidator
import com.jobradar.app.fake.FakeAnalysisRepository
import com.jobradar.app.fake.FakeDocumentFileInspector
import com.jobradar.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class UploadCvValidationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fileInspector: FakeDocumentFileInspector
    private lateinit var viewModel: UploadCvViewModel

    @Before
    fun setUp() {
        fileInspector = FakeDocumentFileInspector()
        viewModel = UploadCvViewModel(
            documentFileInspector = fileInspector,
            analysisRepository = FakeAnalysisRepository(),
            activeAnalysisStore = ActiveAnalysisStore(),
        )
    }

    @Test
    fun `rejects CV larger than 5MB`() = runTest(mainDispatcherRule.testDispatcher) {
        val uri = "content://oversized.pdf"
        fileInspector.stub(
            uri,
            AppResult.Success(
                SelectedDocument(
                    uriString = uri,
                    displayName = "oversized.pdf",
                    sizeBytes = DocumentFileValidator.MAX_FILE_SIZE_BYTES + 1L,
                    mimeType = DocumentFileValidator.MIME_PDF,
                ),
            ),
        )

        viewModel.onCvFileSelected(uri)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedCv)
        assertEquals(R.string.upload_error_file_too_large, viewModel.uiState.value.cvValidationError)
        assertFalse(viewModel.uiState.value.canStartAnalysis)
    }

    @Test
    fun `rejects empty CV of 0 bytes`() = runTest(mainDispatcherRule.testDispatcher) {
        val uri = "content://empty.pdf"
        fileInspector.stub(
            uri,
            AppResult.Success(
                SelectedDocument(
                    uriString = uri,
                    displayName = "empty.pdf",
                    sizeBytes = 0L,
                    mimeType = DocumentFileValidator.MIME_PDF,
                ),
            ),
        )

        viewModel.onCvFileSelected(uri)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedCv)
        assertEquals(R.string.upload_error_file_empty, viewModel.uiState.value.cvValidationError)
    }

    @Test
    fun `rejects unsupported CV file format`() = runTest(mainDispatcherRule.testDispatcher) {
        val uri = "content://resume.docx"
        fileInspector.stub(
            uri,
            AppResult.Success(
                SelectedDocument(
                    uriString = uri,
                    displayName = "resume.docx",
                    sizeBytes = 20_000L,
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ),
            ),
        )

        viewModel.onCvFileSelected(uri)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedCv)
        assertEquals(R.string.upload_error_cv_unsupported_format, viewModel.uiState.value.cvValidationError)
    }

    @Test
    fun `accepts CV with octet-stream MIME when filename ends in pdf`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val uri = "content://fallback.pdf"
            val doc = SelectedDocument(
                uriString = uri,
                displayName = "fallback.pdf",
                sizeBytes = 100_000L,
                mimeType = DocumentFileValidator.MIME_OCTET_STREAM,
            )
            fileInspector.stub(uri, AppResult.Success(doc))

            viewModel.onCvFileSelected(uri)
            advanceUntilIdle()

            assertEquals(doc, viewModel.uiState.value.selectedCv)
            assertNull(viewModel.uiState.value.cvValidationError)
            assertTrue(viewModel.uiState.value.canStartAnalysis)
        }

    @Test
    fun `validates LinkedIn file rejects non-PDF, oversized, and empty files`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val imageUri = "content://li.png"
            fileInspector.stub(
                imageUri,
                AppResult.Success(
                    SelectedDocument(imageUri, "li.png", 10_000L, DocumentFileValidator.MIME_PNG),
                ),
            )
            viewModel.onLinkedInPdfSelected(imageUri)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.selectedLinkedInPdf)
            assertEquals(
                R.string.upload_error_linkedin_unsupported_format,
                viewModel.uiState.value.linkedInValidationError,
            )

            val hugeUri = "content://li_huge.pdf"
            fileInspector.stub(
                hugeUri,
                AppResult.Success(
                    SelectedDocument(
                        hugeUri,
                        "li_huge.pdf",
                        DocumentFileValidator.MAX_FILE_SIZE_BYTES + 10L,
                        DocumentFileValidator.MIME_PDF,
                    ),
                ),
            )
            viewModel.onLinkedInPdfSelected(hugeUri)
            advanceUntilIdle()
            assertEquals(R.string.upload_error_file_too_large, viewModel.uiState.value.linkedInValidationError)

            val emptyUri = "content://li_empty.pdf"
            fileInspector.stub(
                emptyUri,
                AppResult.Success(
                    SelectedDocument(emptyUri, "li_empty.pdf", 0L, DocumentFileValidator.MIME_PDF),
                ),
            )
            viewModel.onLinkedInPdfSelected(emptyUri)
            advanceUntilIdle()
            assertEquals(R.string.upload_error_file_empty, viewModel.uiState.value.linkedInValidationError)
        }
}
