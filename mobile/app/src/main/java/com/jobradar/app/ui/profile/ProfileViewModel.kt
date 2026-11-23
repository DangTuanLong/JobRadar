package com.jobradar.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jobradar.app.R
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AnalysisJob
import com.jobradar.app.domain.model.AnalysisJobStatus
import com.jobradar.app.domain.model.SkillCategory
import com.jobradar.app.domain.model.SkillItem
import com.jobradar.app.domain.model.SkillLevel
import com.jobradar.app.domain.repository.AnalysisRepository
import com.jobradar.app.domain.repository.ProfileRepository
import com.jobradar.app.domain.state.ActiveAnalysisStore
import com.jobradar.app.ui.upload.mapUploadErrorMessageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val analysisRepository: AnalysisRepository,
    private val activeAnalysisStore: ActiveAnalysisStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    val latestCompletedJobId: StateFlow<String?> = activeAnalysisStore.latestCompletedJobId

    private val _events = Channel<ProfileEvent>(Channel.BUFFERED)
    val events: Flow<ProfileEvent> = _events.receiveAsFlow()

    private var hasLoadedOnce = false
    private var lastObservedJobId: String? = null
    private var loadJob: Job? = null
    private var rerunJob: Job? = null

    fun syncWithCompletedJob(jobId: String?) {
        val hasNewCompletedJob = jobId != null && jobId != lastObservedJobId
        if (!hasLoadedOnce || hasNewCompletedJob) {
            lastObservedJobId = jobId
            loadProfile(silentRefresh = hasLoadedOnce)
        }
    }

    fun refreshProfile() {
        loadProfile(silentRefresh = _uiState.value.contentState is ProfileContentState.Loaded)
    }

    fun loadProfile(silentRefresh: Boolean = false) {
        loadJob?.cancel()
        _uiState.update { state ->
            if (silentRefresh && state.contentState is ProfileContentState.Loaded) {
                state.copy(isRefreshing = true)
            } else {
                state.copy(contentState = ProfileContentState.Loading, isRefreshing = false)
            }
        }

        loadJob = viewModelScope.launch {
            when (val result = profileRepository.getMyProfile()) {
                is AppResult.Success -> {
                    hasLoadedOnce = true
                    _uiState.update {
                        it.copy(contentState = ProfileContentState.Loaded(result.data), isRefreshing = false)
                    }
                }

                is AppResult.Error -> {
                    hasLoadedOnce = true
                    handleLoadError(result.code)
                }
            }
        }
    }

    fun openEditMode() {
        val loaded = _uiState.value.contentState as? ProfileContentState.Loaded ?: return
        _uiState.update { it.copy(editDraft = ProfileEditDraft.from(loaded.profile)) }
    }

    fun dismissEditMode() {
        if (_uiState.value.editDraft?.isSaving == true) return
        _uiState.update { it.copy(editDraft = null) }
    }

    fun onEditFullNameChange(value: String) = updateDraft { it.copy(fullName = value) }
    fun onEditHeadlineChange(value: String) = updateDraft { it.copy(headline = value) }
    fun onEditYearsChange(value: String) = updateDraft { it.copy(yearsOfExperienceText = value, yearsError = null) }
    fun onEditSummaryChange(value: String) = updateDraft { it.copy(profileSummary = value) }
    fun onEditGithubUrlChange(value: String) = updateDraft { it.copy(githubUrl = value) }
    fun onEditPortfolioUrlChange(value: String) = updateDraft { it.copy(portfolioUrl = value) }
    fun onEditLinkedinUrlChange(value: String) = updateDraft { it.copy(linkedinUrl = value) }
    fun onNewSkillNameChange(value: String) = updateDraft { it.copy(newSkillName = value, skillInputError = null) }
    fun onNewSkillLevelChange(level: SkillLevel) = updateDraft { it.copy(newSkillLevel = level) }

    fun addSkill() {
        val draft = _uiState.value.editDraft ?: return
        val errorRes = ProfileInputValidator.validateNewSkill(draft.newSkillName, draft.skills)
        if (errorRes != null) {
            updateDraft { it.copy(skillInputError = errorRes) }
            return
        }
        val newItem = SkillItem(
            name = draft.newSkillName.trim(),
            category = SkillCategory.OTHER,
            level = draft.newSkillLevel,
            verifiedBy = emptyList(),
        )
        updateDraft { it.copy(skills = it.skills + newItem, newSkillName = "", skillInputError = null) }
    }

    fun removeSkill(skillName: String) {
        updateDraft { draft ->
            draft.copy(
                skills = draft.skills.filterNot { it.name.equals(skillName, ignoreCase = true) },
                skillInputError = null,
            )
        }
    }

    fun saveProfileEdits() {
        val draft = _uiState.value.editDraft ?: return
        if (draft.isSaving) return

        val parsedYears = ProfileInputValidator.parseYearsOfExperience(draft.yearsOfExperienceText)
        if (parsedYears == null) {
            updateDraft { it.copy(yearsError = R.string.profile_error_years_invalid) }
            return
        }

        updateDraft { it.copy(isSaving = true, yearsError = null) }
        val payload = draft.toUpdatePayload(parsedYears)

        viewModelScope.launch {
            when (val result = profileRepository.updateMyProfile(payload)) {
                is AppResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            contentState = ProfileContentState.Loaded(result.data),
                            editDraft = null,
                            suggestRerun = true,
                            rerunState = RerunMatchingState.Idle,
                        )
                    }
                    _events.send(ProfileEvent.ShowMessage(R.string.profile_save_success))
                }

                is AppResult.Error -> {
                    updateDraft { it.copy(isSaving = false) }
                    val errorRes = mapProfileErrorMessageRes(result.code, fallbackRes = R.string.profile_error_save)
                    _events.send(ProfileEvent.ShowMessage(errorRes))
                }
            }
        }
    }

    fun rerunMatching() {
        if (_uiState.value.isRerunActive) return
        if (_uiState.value.contentState !is ProfileContentState.Loaded) return

        rerunJob?.cancel()
        _uiState.update { it.copy(rerunState = RerunMatchingState.Starting) }

        rerunJob = viewModelScope.launch {
            when (val triggerResult = profileRepository.rerunMatches()) {
                is AppResult.Error -> {
                    val errorRes = mapProfileErrorMessageRes(
                        triggerResult.code,
                        fallbackRes = R.string.upload_error_analysis_failed,
                    )
                    _uiState.update {
                        it.copy(rerunState = RerunMatchingState.Failed(errorRes, triggerResult.message))
                    }
                }

                is AppResult.Success -> {
                    val createdJob = triggerResult.data
                    applyRerunJobUpdate(createdJob)
                    if (!createdJob.status.isTerminal) {
                        observeRerunPolling(createdJob.id)
                    }
                }
            }
        }
    }

    fun cancelRerunTracking() {
        rerunJob?.cancel()
        rerunJob = null
        _uiState.update { it.copy(rerunState = RerunMatchingState.Idle) }
    }

    private suspend fun observeRerunPolling(jobId: String) {
        analysisRepository.observeJobStatus(jobId).collect { result ->
            when (result) {
                is AppResult.Success -> applyRerunJobUpdate(result.data)
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            rerunState = RerunMatchingState.Failed(
                                messageRes = mapUploadErrorMessageRes(result.code),
                                detailMessage = result.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun applyRerunJobUpdate(job: AnalysisJob) {
        when (job.status) {
            AnalysisJobStatus.PENDING,
            AnalysisJobStatus.EXTRACTING,
            AnalysisJobStatus.ENRICHING,
            AnalysisJobStatus.MATCHING,
            -> _uiState.update { it.copy(rerunState = RerunMatchingState.Running(job)) }

            AnalysisJobStatus.COMPLETED -> {
                lastObservedJobId = job.id
                activeAnalysisStore.setLatestCompletedJobId(job.id)
                _uiState.update {
                    it.copy(rerunState = RerunMatchingState.Completed(job), suggestRerun = false)
                }
                _events.send(ProfileEvent.RerunCompleted(R.string.profile_rerun_completed_message))
            }

            AnalysisJobStatus.FAILED -> {
                _uiState.update {
                    it.copy(
                        rerunState = RerunMatchingState.Failed(
                            messageRes = mapUploadErrorMessageRes(job.errorCode),
                            detailMessage = job.errorMessage,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun handleLoadError(code: String) {
        if (code == PROFILE_NOT_FOUND_CODE) {
            _uiState.update {
                it.copy(contentState = ProfileContentState.Empty, isRefreshing = false, editDraft = null)
            }
            return
        }

        val messageRes = mapProfileErrorMessageRes(code)
        if (_uiState.value.contentState is ProfileContentState.Loaded) {
            _uiState.update { it.copy(isRefreshing = false) }
            _events.send(ProfileEvent.ShowMessage(messageRes))
        } else {
            _uiState.update {
                it.copy(contentState = ProfileContentState.Error(messageRes), isRefreshing = false)
            }
        }
    }

    private fun updateDraft(transform: (ProfileEditDraft) -> ProfileEditDraft) {
        _uiState.update { state ->
            val currentDraft = state.editDraft ?: return@update state
            state.copy(editDraft = transform(currentDraft))
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        rerunJob?.cancel()
    }
}
