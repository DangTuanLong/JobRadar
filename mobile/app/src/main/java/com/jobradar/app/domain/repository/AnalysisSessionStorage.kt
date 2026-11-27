package com.jobradar.app.domain.repository

/**
 * Snapshot of the persisted analysis session metadata stored on disk.
 */
data class StoredAnalysisSession(
    val userId: String?,
    val latestCompletedJobId: String?,
)

/**
 * Persistent storage abstraction for the user-scoped latest completed analysis job ID.
 */
interface AnalysisSessionStorage {
    suspend fun read(): StoredAnalysisSession

    suspend fun save(userId: String, jobId: String)

    suspend fun clear()
}
