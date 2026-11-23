package com.jobradar.app.ui.upload

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.SelectedDocument

@Immutable
data class UploadCvUiState(
    val selectedCv: SelectedDocument? = null,
    val selectedLinkedInPdf: SelectedDocument? = null,
    @param:StringRes val cvValidationError: Int? = null,
    @param:StringRes val linkedInValidationError: Int? = null,
    val phase: UploadCvPhase = UploadCvPhase.Idle,
    val elapsedSeconds: Int = 0,
) {
    val isProcessing: Boolean
        get() = phase is UploadCvPhase.Uploading || phase is UploadCvPhase.Tracking

    val canStartAnalysis: Boolean
        get() = selectedCv != null && cvValidationError == null && !isProcessing
}

sealed interface UploadCvPhase {
    data object Idle : UploadCvPhase

    data object Uploading : UploadCvPhase

    data class Tracking(val job: AnalysisJob) : UploadCvPhase

    data class Completed(val job: AnalysisJob) : UploadCvPhase

    data class Failed(
        @param:StringRes val messageRes: Int,
        val detailMessage: String? = null,
        val failedJob: AnalysisJob? = null,
    ) : UploadCvPhase
}
