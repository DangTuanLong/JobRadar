package com.jobradar.app.data.repository

import com.jobradar.app.domain.model.SignUpOutcome
import dagger.Lazy
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around Supabase Auth SDK calls so [AuthRepositoryImpl] can be unit-tested offline.
 */
interface SupabaseAuthGateway {
    val sessionStatus: Flow<SessionStatus>

    suspend fun signInWithEmail(email: String, password: String)

    suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome

    suspend fun signOutRemote()

    suspend fun clearLocalSession()

    fun currentAccessTokenOrNull(): String?
}

@Singleton
class SupabaseAuthGatewayImpl @Inject constructor(
    private val supabase: Lazy<SupabaseClient>,
) : SupabaseAuthGateway {

    override val sessionStatus: Flow<SessionStatus>
        get() = supabase.get().auth.sessionStatus

    override suspend fun signInWithEmail(email: String, password: String) {
        supabase.get().auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome {
        val auth = supabase.get().auth
        auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        return if (auth.currentSessionOrNull() != null) {
            SignUpOutcome.SIGNED_IN
        } else {
            SignUpOutcome.CONFIRMATION_REQUIRED
        }
    }

    override suspend fun signOutRemote() {
        supabase.get().auth.signOut(scope = SignOutScope.LOCAL)
    }

    /**
     * In supabase-kt 3.2.6, `auth.signOut(SignOutScope.LOCAL)` still sends `POST /logout` when a
     * session exists in memory. `clearSession()` clears stored tokens without any network call.
     */
    override suspend fun clearLocalSession() {
        supabase.get().auth.clearSession()
    }

    override fun currentAccessTokenOrNull(): String? =
        supabase.get().auth.currentAccessTokenOrNull()
}
