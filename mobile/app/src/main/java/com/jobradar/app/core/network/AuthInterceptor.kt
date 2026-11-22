package com.jobradar.app.core.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Attaches the Supabase JWT access token to outgoing API requests when available.
 */
class AuthInterceptor @Inject constructor(
    private val tokenProvider: SessionTokenProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = tokenProvider.currentAccessToken()?.trim()
        if (token.isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }

        val authenticatedRequest = originalRequest.newBuilder()
            .header(
                NetworkConstants.HEADER_AUTHORIZATION,
                "${NetworkConstants.BEARER_PREFIX}$token",
            )
            .build()
        return chain.proceed(authenticatedRequest)
    }
}
