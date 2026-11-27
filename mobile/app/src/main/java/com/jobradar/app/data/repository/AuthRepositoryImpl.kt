package com.jobradar.app.data.repository

import com.jobradar.app.core.config.AppConfig
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.core.network.SessionTokenProvider
import com.jobradar.app.data.cache.MatchCacheStore
import com.jobradar.app.domain.model.AuthErrorCodes
import com.jobradar.app.domain.model.AuthSessionState
import com.jobradar.app.domain.model.SignUpOutcome
import com.jobradar.app.domain.repository.AuthRepository
import com.jobradar.app.domain.state.ActiveAnalysisStore
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

private val logger = Logger.getLogger("AuthRepository")

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authGateway: SupabaseAuthGateway,
    private val config: AppConfig,
    private val activeAnalysisStore: ActiveAnalysisStore,
    private val matchCacheStore: MatchCacheStore,
) : AuthRepository, SessionTokenProvider {

    override val sessionState: Flow<AuthSessionState> =
        if (!config.isSupabaseConfigured) {
            flow {
                clearLocalUserCaches()
                emit(AuthSessionState.Unauthenticated)
            }
        } else {
            flow {
                emitAll(authGateway.sessionStatus.map(::mapAndSyncSessionState))
            }.distinctUntilChanged()
        }

    override suspend fun signIn(email: String, password: String): AppResult<Unit> {
        if (!config.isSupabaseConfigured) {
            return configMissingError()
        }
        return try {
            authGateway.signInWithEmail(email = email, password = password)
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Sign in failed", e)
            e.toAuthError()
        }
    }

    override suspend fun signUp(email: String, password: String): AppResult<SignUpOutcome> {
        if (!config.isSupabaseConfigured) {
            return configMissingError()
        }
        return try {
            val outcome = authGateway.signUpWithEmail(email = email, password = password)
            AppResult.Success(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Sign up failed", e)
            e.toAuthError()
        }
    }

    override suspend fun signOut(): AppResult<Unit> {
        if (!config.isSupabaseConfigured) {
            return configMissingError()
        }
        return try {
            try {
                authGateway.signOutRemote()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isNetworkOrTimeoutFailure()) {
                    logger.log(
                        Level.FINE,
                        "Remote sign-out failed while offline; falling back to local session cleanup",
                        e,
                    )
                    authGateway.clearLocalSession()
                } else {
                    throw e
                }
            }
            clearLocalUserCaches()
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Sign out failed", e)
            e.toAuthError()
        }
    }

    override fun currentAccessToken(): String? {
        if (!config.isSupabaseConfigured) {
            return null
        }
        return authGateway.currentAccessTokenOrNull()
    }

    private suspend fun mapAndSyncSessionState(status: SessionStatus): AuthSessionState = when (status) {
        SessionStatus.Initializing -> AuthSessionState.Loading
        is SessionStatus.Authenticated -> {
            val userId = status.session.user?.id.orEmpty()
            val switchedAccount = activeAnalysisStore.onAuthenticated(userId)
            if (switchedAccount) {
                matchCacheStore.clear()
            }
            AuthSessionState.Authenticated(
                userId = userId,
                email = status.session.user?.email,
            )
        }

        is SessionStatus.NotAuthenticated -> {
            clearLocalUserCaches()
            AuthSessionState.Unauthenticated
        }

        is SessionStatus.RefreshFailure -> {
            logger.log(Level.WARNING, "Session refresh failed")
            clearLocalUserCaches()
            AuthSessionState.Unauthenticated
        }
    }

    private suspend fun clearLocalUserCaches() {
        activeAnalysisStore.clear()
        matchCacheStore.clear()
    }

    private fun configMissingError(): AppResult.Error = AppResult.Error(
        code = AuthErrorCodes.CONFIG_MISSING,
        message = "Supabase configuration is missing.",
    )
}
