package com.jobradar.app.domain.state

import com.jobradar.app.domain.repository.AnalysisSessionStorage
import com.jobradar.app.domain.repository.StoredAnalysisSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds and persists the latest completed analysis job ID scoped to the active authenticated user.
 */
@Singleton
class ActiveAnalysisStore @Inject constructor(
    private val storage: AnalysisSessionStorage,
) {

    constructor() : this(NoOpAnalysisSessionStorage)

    private val mutex = Mutex()
    private var activeUserId: String? = null

    private val _latestCompletedJobId = MutableStateFlow<String?>(null)
    val latestCompletedJobId: StateFlow<String?> = _latestCompletedJobId.asStateFlow()

    /**
     * Synchronizes store state for [userId], restoring persisted job ID only when it matches.
     * Returns `true` when switching away from a different account or purging stale foreign state.
     */
    suspend fun onAuthenticated(userId: String): Boolean = mutex.withLock {
        val cleanUserId = userId.trim()
        if (cleanUserId.isEmpty()) {
            val hadPrevious = activeUserId != null || _latestCompletedJobId.value != null
            activeUserId = null
            _latestCompletedJobId.value = null
            storage.clear()
            return hadPrevious
        }

        if (activeUserId == cleanUserId) {
            return false
        }

        val stored = storage.read()
        val switchedAccount =
            (activeUserId != null && activeUserId != cleanUserId) ||
                (stored.userId != null && stored.userId != cleanUserId)

        if (stored.userId == cleanUserId && !stored.latestCompletedJobId.isNullOrBlank()) {
            activeUserId = cleanUserId
            _latestCompletedJobId.value = stored.latestCompletedJobId
        } else {
            if (stored.userId != null || stored.latestCompletedJobId != null) {
                storage.clear()
            }
            activeUserId = cleanUserId
            _latestCompletedJobId.value = null
        }

        return switchedAccount
    }

    suspend fun setLatestCompletedJobId(jobId: String) {
        val cleanJobId = jobId.trim()
        if (cleanJobId.isEmpty()) return

        mutex.withLock {
            _latestCompletedJobId.value = cleanJobId
            val userId = activeUserId
            if (!userId.isNullOrBlank()) {
                storage.save(userId = userId, jobId = cleanJobId)
            }
        }
    }

    suspend fun clear() {
        mutex.withLock {
            activeUserId = null
            _latestCompletedJobId.value = null
            storage.clear()
        }
    }
}

private object NoOpAnalysisSessionStorage : AnalysisSessionStorage {
    override suspend fun read(): StoredAnalysisSession = StoredAnalysisSession(
        userId = null,
        latestCompletedJobId = null,
    )

    override suspend fun save(userId: String, jobId: String) = Unit

    override suspend fun clear() = Unit
}
