package com.jobradar.app.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private var currentToken: String? = null
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { currentToken })
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `attaches Bearer token header when token is present`() {
        currentToken = "jwt-token-abc"
        server.enqueue(MockResponse().setResponseCode(200))

        executeHealthRequest()

        val recorded = server.takeRequest()
        assertEquals("Bearer jwt-token-abc", recorded.getHeader("Authorization"))
    }

    @Test
    fun `trims surrounding whitespace before attaching Bearer token`() {
        currentToken = "  jwt-token-trimmed  "
        server.enqueue(MockResponse().setResponseCode(200))

        executeHealthRequest()

        val recorded = server.takeRequest()
        assertEquals("Bearer jwt-token-trimmed", recorded.getHeader("Authorization"))
    }

    @Test
    fun `omits Authorization header when token is null`() {
        currentToken = null
        server.enqueue(MockResponse().setResponseCode(200))

        executeHealthRequest()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `omits Authorization header when token is empty`() {
        currentToken = ""
        server.enqueue(MockResponse().setResponseCode(200))

        executeHealthRequest()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `omits Authorization header when token is blank whitespace`() {
        currentToken = "   "
        server.enqueue(MockResponse().setResponseCode(200))

        executeHealthRequest()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    private fun executeHealthRequest() {
        val request = Request.Builder()
            .url(server.url("/api/v1/health"))
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }
    }
}
