package com.jobradar.app.ui.upload

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobradar.app.R
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.AnalysisJobStatus
import com.jobradar.app.domain.model.SelectedDocument
import com.jobradar.app.domain.validator.DocumentFileValidator
import com.jobradar.app.ui.theme.JobRadarTheme

private val CV_ALLOWED_MIME_TYPES = arrayOf(
    DocumentFileValidator.MIME_PDF,
    DocumentFileValidator.MIME_PNG,
    DocumentFileValidator.MIME_JPEG,
)

private val LINKEDIN_ALLOWED_MIME_TYPES = arrayOf(
    DocumentFileValidator.MIME_PDF,
)

@Composable
fun UploadCvScreen(
    onNavigateToSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UploadCvViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val cvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.onCvFileSelected(uri.toString())
        }
    }

    val linkedInPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.onLinkedInPdfSelected(uri.toString())
        }
    }

    UploadCvContent(
        uiState = uiState,
        onPickCvClick = { cvPickerLauncher.launch(CV_ALLOWED_MIME_TYPES) },
        onClearCvClick = viewModel::onClearCvFile,
        onPickLinkedInClick = { linkedInPickerLauncher.launch(LINKEDIN_ALLOWED_MIME_TYPES) },
        onClearLinkedInClick = viewModel::onClearLinkedInPdf,
        onStartAnalysisClick = viewModel::startAnalysis,
        onCancelTrackingClick = viewModel::cancelTracking,
        onRetryClick = viewModel::retryAnalysis,
        onResetClick = viewModel::resetForNewScan,
        onViewSuggestionsClick = onNavigateToSuggestions,
        modifier = modifier,
    )
}

@Composable
fun UploadCvContent(
    uiState: UploadCvUiState,
    onPickCvClick: () -> Unit,
    onClearCvClick: () -> Unit,
    onPickLinkedInClick: () -> Unit,
    onClearLinkedInClick: () -> Unit,
    onStartAnalysisClick: () -> Unit,
    onCancelTrackingClick: () -> Unit,
    onRetryClick: () -> Unit,
    onResetClick: () -> Unit,
    onViewSuggestionsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val canEditPickers = !uiState.isProcessing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.upload_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.upload_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DocumentPickerCard(
            title = stringResource(R.string.upload_cv_card_title),
            hint = stringResource(R.string.upload_cv_card_hint),
            document = uiState.selectedCv,
            errorRes = uiState.cvValidationError,
            enabled = canEditPickers,
            onPickClick = onPickCvClick,
            onClearClick = onClearCvClick,
        )

        DocumentPickerCard(
            title = stringResource(R.string.upload_linkedin_card_title),
            hint = stringResource(R.string.upload_linkedin_card_hint),
            document = uiState.selectedLinkedInPdf,
            errorRes = uiState.linkedInValidationError,
            enabled = canEditPickers,
            onPickClick = onPickLinkedInClick,
            onClearClick = onClearLinkedInClick,
        )

        when (val phase = uiState.phase) {
            UploadCvPhase.Idle -> {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onStartAnalysisClick,
                    enabled = uiState.canStartAnalysis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Text(text = stringResource(R.string.upload_action_start))
                }
            }

            UploadCvPhase.Uploading,
            is UploadCvPhase.Tracking,
            -> {
                AnalysisProgressCard(
                    phase = phase,
                    elapsedSeconds = uiState.elapsedSeconds,
                    onCancelClick = onCancelTrackingClick,
                )
            }

            is UploadCvPhase.Completed -> {
                AnalysisCompletedCard(
                    onViewSuggestions = onViewSuggestionsClick,
                    onScanAnother = onResetClick,
                )
            }

            is UploadCvPhase.Failed -> {
                AnalysisErrorCard(
                    messageRes = phase.messageRes,
                    detailMessage = phase.detailMessage,
                    onRetry = onRetryClick,
                    onReset = onResetClick,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UploadCvIdlePreview() {
    JobRadarTheme {
        UploadCvContent(
            uiState = UploadCvUiState(
                selectedCv = SelectedDocument(
                    uriString = "content://cv.pdf",
                    displayName = "NguyenVanA_Android_CV.pdf",
                    sizeBytes = 640_000L,
                    mimeType = DocumentFileValidator.MIME_PDF,
                ),
            ),
            onPickCvClick = {},
            onClearCvClick = {},
            onPickLinkedInClick = {},
            onClearLinkedInClick = {},
            onStartAnalysisClick = {},
            onCancelTrackingClick = {},
            onRetryClick = {},
            onResetClick = {},
            onViewSuggestionsClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UploadCvTrackingPreview() {
    JobRadarTheme {
        UploadCvContent(
            uiState = UploadCvUiState(
                selectedCv = SelectedDocument(
                    uriString = "content://cv.pdf",
                    displayName = "NguyenVanA_Android_CV.pdf",
                    sizeBytes = 640_000L,
                    mimeType = DocumentFileValidator.MIME_PDF,
                ),
                phase = UploadCvPhase.Tracking(
                    AnalysisJob(
                        id = "job-123",
                        userId = "user-1",
                        profileId = null,
                        status = AnalysisJobStatus.ENRICHING,
                        progressPercent = 55,
                        currentStepMessage = "Enriching profile from GitHub and Portfolio...",
                        errorCode = null,
                        errorMessage = null,
                        createdAt = null,
                        updatedAt = null,
                    ),
                ),
                elapsedSeconds = 14,
            ),
            onPickCvClick = {},
            onClearCvClick = {},
            onPickLinkedInClick = {},
            onClearLinkedInClick = {},
            onStartAnalysisClick = {},
            onCancelTrackingClick = {},
            onRetryClick = {},
            onResetClick = {},
            onViewSuggestionsClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UploadCvCompletedPreview() {
    JobRadarTheme {
        UploadCvContent(
            uiState = UploadCvUiState(
                selectedCv = SelectedDocument(
                    uriString = "content://cv.pdf",
                    displayName = "NguyenVanA_Android_CV.pdf",
                    sizeBytes = 640_000L,
                    mimeType = DocumentFileValidator.MIME_PDF,
                ),
                phase = UploadCvPhase.Completed(
                    AnalysisJob(
                        id = "job-123",
                        userId = "user-1",
                        profileId = "profile-1",
                        status = AnalysisJobStatus.COMPLETED,
                        progressPercent = 100,
                        currentStepMessage = "Analysis completed.",
                        errorCode = null,
                        errorMessage = null,
                        createdAt = null,
                        updatedAt = null,
                    ),
                ),
                elapsedSeconds = 26,
            ),
            onPickCvClick = {},
            onClearCvClick = {},
            onPickLinkedInClick = {},
            onClearLinkedInClick = {},
            onStartAnalysisClick = {},
            onCancelTrackingClick = {},
            onRetryClick = {},
            onResetClick = {},
            onViewSuggestionsClick = {},
        )
    }
}
