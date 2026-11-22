package com.jobradar.app.domain.repository

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AuthSessionState
import com.jobradar.app.domain.model.SignUpOutcome
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val sessionState: Flow<AuthSessionState>

    suspend fun signIn(email: String, password: String): AppResult<Unit>

    suspend fun signUp(email: String, password: String): AppResult<SignUpOutcome>

    suspend fun signOut(): AppResult<Unit>

    fun currentAccessToken(): String?
}
