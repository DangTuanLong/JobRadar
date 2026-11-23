package com.jobradar.app.ui.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.AnalysisJobStatus
import com.jobradar.app.domain.repository.AnalysisRepository
import com.jobradar.app.domain.repository.DocumentFileInspector
import com.jobradar.app.domain.state.ActiveAnalysisStore
import com.jobradar.app.domain.validator.DocumentFileValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TICKER_INTERVAL_MILLIS = 1_000L

@HiltViewModel
class UploadCvViewModel @Inject constructor(
    private val documentFileInspector: DocumentFileInspector,
    private val analysisRepository: AnalysisRepository,
    private val activeAnalysisStore: ActiveAnalysisStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UploadCvUiState())
    val uiState: StateFlow<UploadCvUiState> = _uiState.asStateFlow()

    private var activeTrackingJob: Job? = null

    fun onCvFileSelected(uriString: String) {
        if (_uiState.value.isProcessing) return
        viewModelScope.launch {
            when (val result = documentFileInspector.inspect(uriString)) {
                is AppResult.Success -> {
                    val doc = result.data
                    val validationError = DocumentFileValidator.validateCvDocument(doc)
                    if (validationError == null) {
                        _uiState.update { state ->
                            state.copy(
                                selectedCv = doc,
                                cvValidationError = null,
                                phase = resetFinishedPhase(state.phase),
                            )
                        }
                    } else {
                        _uiState.update { state ->
                            state.copy(
                                selectedCv = null,
                                cvValidationError = mapDocumentValidationError(validationError),
                                phase = resetFinishedPhase(state.phase),
                            )
                        }
                    }
                }

                is AppResult.Error -> {
                    _uiState.update { state ->
                        state.copy(
                            selectedCv = null,
                            cvValidationError = mapUploadErrorMessageRes(result.code),
                            phase = resetFinishedPhase(state.phase),
                        )
                    }
                }
            }
        }
    }

    fun onClearCvFile() {
        if (_uiState.value.isProcessing) return
        _uiState.update { state ->
            state.copy(
                selectedCv = null,
                cvValidationError = null,
                phase = resetFinishedPhase(state.phase),
            )
        }
    }

    fun onLinkedInPdfSelected(uriString: String) {
        if (_uiState.value.isProcessing) return
        viewModelScope.launch {
            when (val result = documentFileInspector.inspect(uriString)) {
                is AppResult.Success -> {
                    val doc = result.data
                    val validationError = DocumentFileValidator.validateLinkedInPdf(doc)
                    if (validationError == null) {
                        _uiState.update { state ->
                            state.copy(
                                selectedLinkedInPdf = doc,
                                linkedInValidationError = null,
                                phase = resetFinishedPhase(state.phase),
                            )
                        }
                    } else {
                        _uiState.update { state ->
                            state.copy(
                                selectedLinkedInPdf = null,
                                linkedInValidationError = mapDocumentValidationError(validationError),
                                phase = resetFinishedPhase(state.phase),
                            )
                        }
                    }
                }

                is AppResult.Error -> {
                    _uiState.update { state ->
                        state.copy(
                            selectedLinkedInPdf = null,
                            linkedInValidationError = mapUploadErrorMessageRes(result.code),
                            phase = resetFinishedPhase(state.phase),
                        )
                    }
                }
            }
        }
    }

    fun onClearLinkedInPdf() {
        if (_uiState.value.isProcessing) return
        _uiState.update { state ->
            state.copy(
                selectedLinkedInPdf = null,
                linkedInValidationError = null,
                phase = resetFinishedPhase(state.phase),
            )
        }
    }

    fun startAnalysis() {
        val current = _uiState.value
        val cvDocument = current.selectedCv ?: return
        if (!current.canStartAnalysis) return

        activeTrackingJob?.cancel()
        _uiState.update { state ->
            state.copy(
                phase = UploadCvPhase.Uploading,
                elapsedSeconds = 0,
            )
        }

        activeTrackingJob = viewModelScope.launch {
            val tickerJob = launch {
                while (true) {
                    delay(TICKER_INTERVAL_MILLIS)
                    _uiState.update { state ->
                        state.copy(elapsedSeconds = state.elapsedSeconds + 1)
                    }
                }
            }
            try {
                val uploadResult = analysisRepository.analyzeCv(
                    cvDocument = cvDocument,
                    linkedInPdf = current.selectedLinkedInPdf,
                )
                when (uploadResult) {
                    is AppResult.Error -> {
                        _uiState.update { state ->
                            state.copy(
                                phase = UploadCvPhase.Failed(
                                    messageRes = mapUploadErrorMessageRes(uploadResult.code),
                                    detailMessage = uploadResult.message,
                                ),
                            )
                        }
                    }

                    is AppResult.Success -> {
                        val createdJob = uploadResult.data
                        applyJobUpdate(createdJob)
                        if (!createdJob.status.isTerminal) {
                            observeJobPolling(createdJob.id)
                        }
                    }
                }
            } finally {
                tickerJob.cancel()
            }
        }
    }

    fun cancelTracking() {
        activeTrackingJob?.cancel()
        activeTrackingJob = null
        _uiState.update { state ->
            state.copy(
                phase = UploadCvPhase.Idle,
                elapsedSeconds = 0,
            )
        }
    }

    fun retryAnalysis() {
        if (_uiState.value.isProcessing) return
        if (_uiState.value.selectedCv != null) {
            startAnalysis()
        }
    }

    fun resetForNewScan() {
        activeTrackingJob?.cancel()
        activeTrackingJob = null
        _uiState.value = UploadCvUiState()
    }

    private suspend fun observeJobPolling(jobId: String) {
        analysisRepository.observeJobStatus(jobId).collect { result ->
            when (result) {
                is AppResult.Success -> applyJobUpdate(result.data)
                is AppResult.Error -> {
                    _uiState.update { state ->
                        state.copy(
                            phase = UploadCvPhase.Failed(
                                messageRes = mapUploadErrorMessageRes(result.code),
                                detailMessage = result.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun applyJobUpdate(job: AnalysisJob) {
        when (job.status) {
            AnalysisJobStatus.PENDING,
            AnalysisJobStatus.EXTRACTING,
            AnalysisJobStatus.ENRICHING,
            AnalysisJobStatus.MATCHING,
            -> {
                _uiState.update { state ->
                    state.copy(phase = UploadCvPhase.Tracking(job))
                }
            }

            AnalysisJobStatus.COMPLETED -> {
                activeAnalysisStore.setLatestCompletedJobId(job.id)
                _uiState.update { state ->
                    state.copy(phase = UploadCvPhase.Completed(job))
                }
            }

            AnalysisJobStatus.FAILED -> {
                _uiState.update { state ->
                    state.copy(
                        phase = UploadCvPhase.Failed(
                            messageRes = mapUploadErrorMessageRes(job.errorCode),
                            detailMessage = job.errorMessage,
                            failedJob = job,
                        ),
                    )
                }
            }
        }
    }

    private fun resetFinishedPhase(phase: UploadCvPhase): UploadCvPhase =
        when (phase) {
            is UploadCvPhase.Completed,
            is UploadCvPhase.Failed,
            -> UploadCvPhase.Idle

            UploadCvPhase.Idle,
            UploadCvPhase.Uploading,
            is UploadCvPhase.Tracking,
            -> phase
        }

    override fun onCleared() {
        super.onCleared()
        activeTrackingJob?.cancel()
    }
}
