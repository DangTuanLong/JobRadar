package com.jobradar.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jobradar.app.domain.repository.AnalysisSessionStorage
import com.jobradar.app.domain.repository.StoredAnalysisSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

const val ANALYSIS_SESSION_DATASTORE_NAME = "jobradar_analysis_session"

private val KEY_STORED_USER_ID = stringPreferencesKey("stored_user_id")
private val KEY_LATEST_COMPLETED_JOB_ID = stringPreferencesKey("latest_completed_job_id")
private val logger = Logger.getLogger("AnalysisPreferencesDataStore")

/**
 * Jetpack Preferences DataStore implementation of [AnalysisSessionStorage].
 */
@Singleton
class AnalysisPreferencesDataStore(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher,
) : AnalysisSessionStorage {

    @Inject
    constructor(dataStore: DataStore<Preferences>) : this(
        dataStore = dataStore,
        ioDispatcher = Dispatchers.IO,
    )

    override suspend fun read(): StoredAnalysisSession = withContext(ioDispatcher) {
        val preferences = dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    logger.log(Level.WARNING, "Failed to read analysis preferences", exception)
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .first()

        StoredAnalysisSession(
            userId = preferences[KEY_STORED_USER_ID],
            latestCompletedJobId = preferences[KEY_LATEST_COMPLETED_JOB_ID],
        )
    }

    override suspend fun save(userId: String, jobId: String): Unit = withContext(ioDispatcher) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_STORED_USER_ID] = userId
                preferences[KEY_LATEST_COMPLETED_JOB_ID] = jobId
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.log(Level.WARNING, "Failed to save analysis preferences", e)
        }
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        try {
            dataStore.edit { preferences ->
                preferences.remove(KEY_STORED_USER_ID)
                preferences.remove(KEY_LATEST_COMPLETED_JOB_ID)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.log(Level.WARNING, "Failed to clear analysis preferences", e)
        }
    }
}
