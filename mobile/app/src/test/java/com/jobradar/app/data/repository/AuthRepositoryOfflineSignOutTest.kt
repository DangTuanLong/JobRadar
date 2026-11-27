package com.jobradar.app.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jobradar.app.core.config.AppConfig
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.data.cache.MatchCacheStore
import com.jobradar.app.data.local.AnalysisPreferencesDataStore
import com.jobradar.app.domain.model.AuthSessionState
import com.jobradar.app.domain.model.MatchResult
import com.jobradar.app.domain.model.ScoreBreakdown
import com.jobradar.app.domain.model.SignUpOutcome
import com.jobradar.app.domain.repository.StoredAnalysisSession
import com.jobradar.app.domain.state.ActiveAnalysisStore
import com.jobradar.app.util.MainDispatcherRule
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.HttpRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class AuthRepositoryOfflineSignOutTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `online signOut revokes remote session and clears ActiveAnalysisStore and MatchCacheStore`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val storage = createStorage("online_signout.preferences_pb")
            val activeAnalysisStore = ActiveAnalysisStore(storage)
            val matchCacheStore = MatchCacheStore()
            val gateway = FakeSupabaseAuthGateway().apply {
                mutableSessionStatus.value = authenticatedStatus("user-a", "a@jobradar.vn")
            }
            val repository = createRepository(gateway, activeAnalysisStore, matchCacheStore)

            val initialSession = repository.sessionState.first { it is AuthSessionState.Authenticated }
            assertEquals("user-a", (initialSession as AuthSessionState.Authenticated).userId)

            activeAnalysisStore.setLatestCompletedJobId("job-1")
            matchCacheStore.put("job-1", listOf(sampleMatch("job-1")))

            val result = repository.signOut()

            assertTrue(result is AppResult.Success)
            assertEquals(1, gateway.signOutRemoteCalls)
            assertEquals(0, gateway.clearLocalSessionCalls)
            assertNull(activeAnalysisStore.latestCompletedJobId.value)
            assertEquals(StoredAnalysisSession(null, null), storage.read())
            assertNull(matchCacheStore.get("job-1"))
            assertEquals(AuthSessionState.Unauthenticated, repository.sessionState.first())
        }

    @Test
    fun `offline signOut falls back to clearLocalSession on network or timeout failure`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val failures = listOf(
                HttpRequestException("Offline", HttpRequestBuilder()),
                HttpRequestTimeoutException("https://example.supabase.co/auth/v1/logout", 5_000L),
                IOException("Socket closed"),
            )

            failures.forEachIndexed { index, failure ->
                val storage = createStorage("offline_signout_$index.preferences_pb")
                val activeAnalysisStore = ActiveAnalysisStore(storage)
                val matchCacheStore = MatchCacheStore()
                val gateway = FakeSupabaseAuthGateway().apply {
                    mutableSessionStatus.value = authenticatedStatus("user-a", "a@jobradar.vn")
                    signOutRemoteError = failure
                }
                val repository = createRepository(gateway, activeAnalysisStore, matchCacheStore)

                repository.sessionState.first { it is AuthSessionState.Authenticated }
                activeAnalysisStore.setLatestCompletedJobId("job-$index")
                matchCacheStore.put("job-$index", listOf(sampleMatch("job-$index")))

                val result = repository.signOut()

                assertTrue(result is AppResult.Success)
                assertEquals(1, gateway.signOutRemoteCalls)
                assertEquals(1, gateway.clearLocalSessionCalls)
                assertNull(activeAnalysisStore.latestCompletedJobId.value)
                assertEquals(StoredAnalysisSession(null, null), storage.read())
                assertNull(matchCacheStore.get("job-$index"))
                assertEquals(AuthSessionState.Unauthenticated, repository.sessionState.first())
            }
        }

    @Test
    fun `sessionState hydrates matching user jobId and wipes caches on account switch`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val storage = createStorage("account_switch.preferences_pb")
            storage.save(userId = "user-a", jobId = "job-77")
            val activeAnalysisStore = ActiveAnalysisStore(storage)
            val matchCacheStore = MatchCacheStore().apply {
                put("job-77", listOf(sampleMatch("job-77")))
            }
            val gateway = FakeSupabaseAuthGateway()
            val repository = createRepository(gateway, activeAnalysisStore, matchCacheStore)

            val stateChannel = Channel<AuthSessionState>(Channel.UNLIMITED)
            val collectorJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.sessionState.collect { stateChannel.send(it) }
            }

            assertEquals(AuthSessionState.Loading, stateChannel.receive())

            gateway.mutableSessionStatus.value = authenticatedStatus("user-a", "a@jobradar.vn")
            assertEquals(
                AuthSessionState.Authenticated("user-a", "a@jobradar.vn"),
                stateChannel.receive(),
            )
            assertEquals("job-77", activeAnalysisStore.latestCompletedJobId.value)
            assertEquals(1, matchCacheStore.get("job-77")?.size)

            gateway.mutableSessionStatus.value = authenticatedStatus("user-b", "b@jobradar.vn")
            assertEquals(
                AuthSessionState.Authenticated("user-b", "b@jobradar.vn"),
                stateChannel.receive(),
            )
            assertNull(activeAnalysisStore.latestCompletedJobId.value)
            assertEquals(StoredAnalysisSession(null, null), storage.read())
            assertNull(matchCacheStore.get("job-77"))

            collectorJob.cancel()
        }

    private fun kotlinx.coroutines.test.TestScope.createStorage(
        fileName: String,
    ): AnalysisPreferencesDataStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFolder.newFile(fileName) },
        )
        return AnalysisPreferencesDataStore(
            dataStore = dataStore,
            ioDispatcher = mainDispatcherRule.testDispatcher,
        )
    }

    private fun createRepository(
        gateway: FakeSupabaseAuthGateway,
        activeAnalysisStore: ActiveAnalysisStore,
        matchCacheStore: MatchCacheStore,
    ): AuthRepositoryImpl = AuthRepositoryImpl(
        authGateway = gateway,
        config = AppConfig(
            supabaseUrl = "https://example.supabase.co",
            supabasePublishableKey = "pk_test_123",
            apiBaseUrl = "https://api.example.com/api/v1/",
        ),
        activeAnalysisStore = activeAnalysisStore,
        matchCacheStore = matchCacheStore,
    )

    private fun authenticatedStatus(
        userId: String,
        email: String,
    ): SessionStatus.Authenticated = SessionStatus.Authenticated(
        session = UserSession(
            accessToken = "access-$userId",
            refreshToken = "refresh-$userId",
            expiresIn = 3600L,
            tokenType = "bearer",
            user = UserInfo(
                aud = "authenticated",
                id = userId,
                email = email,
            ),
        ),
        source = SessionSource.Unknown,
    )

    private fun sampleMatch(jobId: String) = MatchResult(
        id = "match-$jobId",
        jobId = jobId,
        profileId = "prof-1",
        companyId = "comp-1",
        jobPostingId = "jp-1",
        companyName = "MoMo",
        companyWebsite = "https://momo.vn",
        companyLocation = "HCMC",
        companyIndustry = "Fintech",
        companySizeRange = "1000+",
        jobTitle = "Android Developer",
        jobLevel = "fresher",
        jobSourceUrl = null,
        matchScore = 90,
        scoreBreakdown = ScoreBreakdown(skills = 92, experience = 85, projects = 90),
        matchedSkills = listOf("Kotlin"),
        missingSkills = emptyList(),
        reasoning = "Great fit",
        webEvidence = emptyList(),
        createdAt = null,
    )
}

private class FakeSupabaseAuthGateway : SupabaseAuthGateway {
    val mutableSessionStatus = MutableStateFlow<SessionStatus>(SessionStatus.Initializing)
    override val sessionStatus: Flow<SessionStatus> = mutableSessionStatus

    var signOutRemoteError: Throwable? = null
    var signOutRemoteCalls = 0
        private set
    var clearLocalSessionCalls = 0
        private set

    override suspend fun signInWithEmail(email: String, password: String) = Unit

    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
        SignUpOutcome.SIGNED_IN

    override suspend fun signOutRemote() {
        signOutRemoteCalls++
        signOutRemoteError?.let { throw it }
        mutableSessionStatus.value = SessionStatus.NotAuthenticated(isSignOut = true)
    }

    override suspend fun clearLocalSession() {
        clearLocalSessionCalls++
        mutableSessionStatus.value = SessionStatus.NotAuthenticated(isSignOut = true)
    }

    override fun currentAccessTokenOrNull(): String? = null
}
