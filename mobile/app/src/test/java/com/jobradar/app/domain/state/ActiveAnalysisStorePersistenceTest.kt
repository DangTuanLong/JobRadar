package com.jobradar.app.domain.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.jobradar.app.data.local.AnalysisPreferencesDataStore
import com.jobradar.app.domain.repository.StoredAnalysisSession
import com.jobradar.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveAnalysisStorePersistenceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `persists jobId for userA and restores on new ActiveAnalysisStore instance`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tempFolder.newFile("analysis_restore.preferences_pb") },
            )
            val storage = AnalysisPreferencesDataStore(
                dataStore = dataStore,
                ioDispatcher = mainDispatcherRule.testDispatcher,
            )

            val initialStore = ActiveAnalysisStore(storage)
            assertFalse(initialStore.onAuthenticated("user-a"))
            initialStore.setLatestCompletedJobId("job-a1")
            assertEquals("job-a1", initialStore.latestCompletedJobId.value)

            val restoredStore = ActiveAnalysisStore(storage)
            assertFalse(restoredStore.onAuthenticated("user-a"))
            assertEquals("job-a1", restoredStore.latestCompletedJobId.value)
        }

    @Test
    fun `switching to userB purges userA jobId from memory and disk`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tempFolder.newFile("analysis_switch.preferences_pb") },
            )
            val storage = AnalysisPreferencesDataStore(
                dataStore = dataStore,
                ioDispatcher = mainDispatcherRule.testDispatcher,
            )

            val store = ActiveAnalysisStore(storage)
            store.onAuthenticated("user-a")
            store.setLatestCompletedJobId("job-a1")

            val switched = store.onAuthenticated("user-b")
            assertTrue(switched)
            assertNull(store.latestCompletedJobId.value)
            assertEquals(StoredAnalysisSession(userId = null, latestCompletedJobId = null), storage.read())

            val reauthUserAStore = ActiveAnalysisStore(storage)
            assertFalse(reauthUserAStore.onAuthenticated("user-a"))
            assertNull(reauthUserAStore.latestCompletedJobId.value)
        }

    @Test
    fun `clear removes jobId from both memory and DataStore`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tempFolder.newFile("analysis_clear.preferences_pb") },
            )
            val storage = AnalysisPreferencesDataStore(
                dataStore = dataStore,
                ioDispatcher = mainDispatcherRule.testDispatcher,
            )

            val store = ActiveAnalysisStore(storage)
            store.onAuthenticated("user-a")
            store.setLatestCompletedJobId("job-a2")
            assertEquals("job-a2", store.latestCompletedJobId.value)

            store.clear()
            assertNull(store.latestCompletedJobId.value)
            assertEquals(StoredAnalysisSession(userId = null, latestCompletedJobId = null), storage.read())

            val freshStore = ActiveAnalysisStore(storage)
            freshStore.onAuthenticated("user-a")
            assertNull(freshStore.latestCompletedJobId.value)
        }

    @Test
    fun `AnalysisPreferencesDataStore falls back safely when DataStore throws IOException`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val failingDataStore = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow {
                    throw IOException("Disk read failure")
                }

                override suspend fun updateData(
                    transform: suspend (t: Preferences) -> Preferences,
                ): Preferences {
                    throw IOException("Disk full")
                }
            }
            val storage = AnalysisPreferencesDataStore(
                dataStore = failingDataStore,
                ioDispatcher = mainDispatcherRule.testDispatcher,
            )

            assertEquals(StoredAnalysisSession(userId = null, latestCompletedJobId = null), storage.read())
            storage.save(userId = "user-a", jobId = "job-1")
            storage.clear()
        }
}
