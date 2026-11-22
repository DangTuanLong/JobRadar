package com.jobradar.app.fake

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AuthSessionState
import com.jobradar.app.domain.model.SignUpOutcome
import com.jobradar.app.domain.repository.AuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAuthRepository : AuthRepository {

    val mutableSessionState = MutableStateFlow<AuthSessionState>(AuthSessionState.Unauthenticated)
    override val sessionState: Flow<AuthSessionState> = mutableSessionState

    var accessToken: String? = null
    var signInResult: AppResult<Unit> = AppResult.Success(Unit)
    var signUpResult: AppResult<SignUpOutcome> = AppResult.Success(SignUpOutcome.SIGNED_IN)
    var signOutResult: AppResult<Unit> = AppResult.Success(Unit)

    var signInDelayGate: CompletableDeferred<Unit>? = null

    var signInCalls = 0
        private set
    var signUpCalls = 0
        private set
    var signOutCalls = 0
        private set

    var lastEmail: String? = null
        private set
    var lastPassword: String? = null
        private set

    override suspend fun signIn(email: String, password: String): AppResult<Unit> {
        signInCalls++
        lastEmail = email
        lastPassword = password
        signInDelayGate?.await()
        return signInResult
    }

    override suspend fun signUp(email: String, password: String): AppResult<SignUpOutcome> {
        signUpCalls++
        lastEmail = email
        lastPassword = password
        return signUpResult
    }

    override suspend fun signOut(): AppResult<Unit> {
        signOutCalls++
        return signOutResult
    }

    override fun currentAccessToken(): String? = accessToken
}
