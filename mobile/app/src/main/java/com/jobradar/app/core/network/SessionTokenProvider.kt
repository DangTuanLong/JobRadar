package com.jobradar.app.core.network

/**
 * Supplies the active bearer access token for authenticated backend requests.
 */
fun interface SessionTokenProvider {
    fun currentAccessToken(): String?
}
