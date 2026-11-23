package com.jobradar.app.data.repository

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.core.network.NetworkConstants
import com.jobradar.app.core.network.NetworkErrorCodes
import com.jobradar.app.core.network.safeApiCall
import com.jobradar.app.data.file.DocumentMultipartFactory
import com.jobradar.app.data.remote.JobRadarApiService
import com.jobradar.app.data.remote.dto.toDomain
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.AnalysisJobStatus
import com.jobradar.app.domain.model.SelectedDocument
import com.jobradar.app.domain.repository.AnalysisRepository
import com.jobradar.app.domain.state.ActiveAnalysisStore
import com.jobradar.app.domain.validator.DocumentFileValidator
import com.jobradar.app.domain.validator.DocumentValidationError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

private val logger = Logger.getLogger("AnalysisRepository")

@Singleton
class AnalysisRepositoryImpl @Inject constructor(
    private val apiService: JobRadarApiService,
    private val json: Json,
    private val multipartFactory: DocumentMultipartFactory,
    private val activeAnalysisStore: ActiveAnalysisStore,
    private val pollingConfig: AnalysisPollingConfig,
) : AnalysisRepository {

    override suspend fun analyzeCv(
        cvDocument: SelectedDocument,
        linkedInPdf: SelectedDocument?,
    ): AppResult<AnalysisJob> {
        DocumentFileValidator.validateCvDocument(cvDocument)?.let { error ->
            return mapValidationError(error)
        }
        if (linkedInPdf != null) {
            DocumentFileValidator.validateLinkedInPdf(linkedInPdf)?.let { error ->
                return mapValidationError(error)
            }
        }

        val cvPart = when (val result = multipartFactory.createPart(cvDocument, NetworkConstants.PART_CV_FILE)) {
            is AppResult.Success -> result.data
            is AppResult.Error -> return result
        }
        val linkedInPart = if (linkedInPdf != null) {
            when (val result = multipartFactory.createPart(linkedInPdf, NetworkConstants.PART_LINKEDIN_PDF)) {
                is AppResult.Success -> result.data
                is AppResult.Error -> return result
            }
        } else {
            null
        }

        return when (val response = safeApiCall(json) { apiService.analyzeCv(cvPart, linkedInPart) }) {
            is AppResult.Success -> {
                val job = response.data.toDomain()
                if (job.status == AnalysisJobStatus.COMPLETED) {
                    activeAnalysisStore.setLatestCompletedJobId(job.id)
                }
                AppResult.Success(job)
            }

            is AppResult.Error -> response
        }
    }

    override fun observeJobStatus(jobId: String): Flow<AppResult<AnalysisJob>> = flow {
        if (jobId.isBlank()) {
            emit(
                AppResult.Error(
                    code = NetworkErrorCodes.NOT_FOUND,
                    message = "Analysis job ID is missing.",
                ),
            )
            return@flow
        }

        var consecutiveTransientErrors = 0
        val finishedWithinTimeout = withTimeoutOrNull(pollingConfig.maxDurationMs) {
            while (true) {
                when (val result = safeApiCall(json) { apiService.getAnalysisJob(jobId) }) {
                    is AppResult.Success -> {
                        consecutiveTransientErrors = 0
                        val job = result.data.toDomain()
                        if (job.status == AnalysisJobStatus.COMPLETED) {
                            activeAnalysisStore.setLatestCompletedJobId(job.id)
                        }
                        emit(AppResult.Success(job))
                        if (job.status.isTerminal) {
                            return@withTimeoutOrNull true
                        }
                    }

                    is AppResult.Error -> {
                        if (isTransientError(result.code)) {
                            consecutiveTransientErrors++
                            logger.log(
                                Level.WARNING,
                                "Transient polling error ({0}/{1}) for job {2}: {3}",
                                arrayOf<Any>(
                                    consecutiveTransientErrors,
                                    pollingConfig.maxConsecutiveTransientErrors,
                                    jobId,
                                    result.code,
                                ),
                            )
                            if (consecutiveTransientErrors >= pollingConfig.maxConsecutiveTransientErrors) {
                                emit(result)
                                return@withTimeoutOrNull true
                            }
                        } else {
                            logger.log(
                                Level.WARNING,
                                "Terminal polling error for job {0}: {1}",
                                arrayOf(jobId, result.code),
                            )
                            emit(result)
                            return@withTimeoutOrNull true
                        }
                    }
                }
                delay(pollingConfig.pollIntervalMs)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }

        if (finishedWithinTimeout == null) {
            logger.log(
                Level.WARNING,
                "Polling timed out after {0} ms for job {1}",
                arrayOf<Any>(pollingConfig.maxDurationMs, jobId),
            )
            emit(
                AppResult.Error(
                    code = NetworkErrorCodes.POLLING_TIMEOUT,
                    message = "Analysis polling exceeded maximum wait duration.",
                ),
            )
        }
    }

    private fun isTransientError(code: String): Boolean =
        code == NetworkErrorCodes.NETWORK_ERROR || code == NetworkErrorCodes.TIMEOUT

    private fun mapValidationError(error: DocumentValidationError): AppResult.Error = when (error) {
        DocumentValidationError.EMPTY_FILE -> AppResult.Error(
            code = "CV_FILE_EMPTY",
            message = "Uploaded file is empty.",
        )

        DocumentValidationError.FILE_TOO_LARGE -> AppResult.Error(
            code = "CV_FILE_TOO_LARGE",
            message = "Uploaded file exceeds 5 MB.",
        )

        DocumentValidationError.UNSUPPORTED_CV_FORMAT,
        DocumentValidationError.UNSUPPORTED_LINKEDIN_FORMAT,
        -> AppResult.Error(
            code = "CV_UNSUPPORTED_FORMAT",
            message = "Unsupported file format.",
        )
    }
}
