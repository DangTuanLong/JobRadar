package com.jobradar.app.data.repository

import android.util.Log
import com.jobradar.app.core.config.AppConfig
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.core.network.SessionTokenProvider
import com.jobradar.app.domain.model.AuthErrorCodes
import com.jobradar.app.domain.model.AuthSessionState
import com.jobradar.app.domain.model.SignUpOutcome
import com.jobradar.app.domain.repository.AuthRepository
import com.jobradar.app.domain.state.ActiveAnalysisStore
import dagger.Lazy
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuthRepository"

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val supabase: Lazy<SupabaseClient>,
    private val config: AppConfig,
    private val activeAnalysisStore: ActiveAnalysisStore,
) : AuthRepository, SessionTokenProvider {

    override val sessionState: Flow<AuthSessionState> =
        if (!config.isSupabaseConfigured) {
            flowOf(AuthSessionState.Unauthenticated)
        } else {
            flow {
                emitAll(supabase.get().auth.sessionStatus.map(::toSessionState))
            }.distinctUntilChanged()
        }

    override suspend fun signIn(email: String, password: String): AppResult<Unit> {
        if (!config.isSupabaseConfigured) {
            return configMissingError()
        }
        return try {
            supabase.get().auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sign in failed", e)
            e.toAuthError()
        }
    }

    override suspend fun signUp(email: String, password: String): AppResult<SignUpOutcome> {
        if (!config.isSupabaseConfigured) {
            return configMissingError()
        }
        return try {
            val auth = supabase.get().auth
            auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            val outcome = if (auth.currentSessionOrNull() != null) {
                SignUpOutcome.SIGNED_IN
            } else {
                SignUpOutcome.CONFIRMATION_REQUIRED
            }
            AppResult.Success(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sign up failed", e)
            e.toAuthError()
        }
    }

    override suspend fun signOut(): AppResult<Unit> {
        if (!config.isSupabaseConfigured) {
            return configMissingError()
        }
        return try {
            supabase.get().auth.signOut()
            activeAnalysisStore.clear()
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sign out failed", e)
            e.toAuthError()
        }
    }

    override fun currentAccessToken(): String? {
        if (!config.isSupabaseConfigured) {
            return null
        }
        return supabase.get().auth.currentAccessTokenOrNull()
    }

    private fun toSessionState(status: SessionStatus): AuthSessionState = when (status) {
        SessionStatus.Initializing -> AuthSessionState.Loading
        is SessionStatus.Authenticated -> AuthSessionState.Authenticated(
            userId = status.session.user?.id.orEmpty(),
            email = status.session.user?.email,
        )

        is SessionStatus.NotAuthenticated -> {
            activeAnalysisStore.clear()
            AuthSessionState.Unauthenticated
        }

        is SessionStatus.RefreshFailure -> {
            Log.w(TAG, "Session refresh failed")
            activeAnalysisStore.clear()
            AuthSessionState.Unauthenticated
        }
    }

    private fun configMissingError(): AppResult.Error = AppResult.Error(
        code = AuthErrorCodes.CONFIG_MISSING,
        message = "Supabase configuration is missing.",
    )
}
