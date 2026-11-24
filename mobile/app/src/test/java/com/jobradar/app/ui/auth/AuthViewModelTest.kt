package com.jobradar.app.ui.auth

import com.jobradar.app.R
import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.AuthErrorCodes
import com.jobradar.app.domain.model.SignUpOutcome
import com.jobradar.app.fake.FakeAuthRepository
import com.jobradar.app.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeAuthRepository
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        repository = FakeAuthRepository()
        viewModel = AuthViewModel(repository)
    }

    @Test
    fun `login with empty email sets empty email error and skips repository`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.onEmailChange("   ")
            viewModel.onPasswordChange("123456")

            viewModel.login()
            advanceUntilIdle()

            assertEquals(R.string.auth_error_email_empty, viewModel.uiState.value.emailError)
            assertEquals(0, repository.signInCalls)
        }

    @Test
    fun `login with invalid email sets invalid email error and skips repository`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.onEmailChange("not-an-email")
            viewModel.onPasswordChange("123456")

            viewModel.login()
            advanceUntilIdle()

            assertEquals(R.string.auth_error_email_invalid, viewModel.uiState.value.emailError)
            assertEquals(0, repository.signInCalls)
        }

    @Test
    fun `login with short password sets password error and skips repository`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.onEmailChange("user@jobradar.vn")
            viewModel.onPasswordChange("12345")

            viewModel.login()
            advanceUntilIdle()

            assertEquals(R.string.auth_error_password_short, viewModel.uiState.value.passwordError)
            assertEquals(0, repository.signInCalls)
        }

    @Test
    fun `login success trims email and clears loading state`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.signInResult = AppResult.Success(Unit)
            viewModel.onEmailChange("  user@jobradar.vn  ")
            viewModel.onPasswordChange("secret123")

            viewModel.login()
            advanceUntilIdle()

            assertEquals(1, repository.signInCalls)
            assertEquals("user@jobradar.vn", repository.lastEmail)
            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.generalError)
        }

    @Test
    fun `login error maps invalid credentials code to message resource`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.signInResult = AppResult.Error(
                code = AuthErrorCodes.INVALID_CREDENTIALS,
                message = "Invalid login credentials",
            )
            viewModel.onEmailChange("user@jobradar.vn")
            viewModel.onPasswordChange("wrongpass")

            viewModel.login()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(
                R.string.auth_error_invalid_credentials,
                viewModel.uiState.value.generalError,
            )
        }

    @Test
    fun `register with mismatched passwords sets confirm error and skips repository`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.onEmailChange("user@jobradar.vn")
            viewModel.onPasswordChange("secret123")
            viewModel.onConfirmPasswordChange("different123")

            viewModel.register()
            advanceUntilIdle()

            assertEquals(
                R.string.auth_error_password_mismatch,
                viewModel.uiState.value.confirmPasswordError,
            )
            assertEquals(0, repository.signUpCalls)
        }

    @Test
    fun `register success signed in clears loading without confirmation event`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.signUpResult = AppResult.Success(SignUpOutcome.SIGNED_IN)
            val events = mutableListOf<AuthEvent>()
            val collectorJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }

            viewModel.onEmailChange("user@jobradar.vn")
            viewModel.onPasswordChange("secret123")
            viewModel.onConfirmPasswordChange("secret123")

            viewModel.register()
            advanceUntilIdle()

            assertEquals(1, repository.signUpCalls)
            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.generalError)
            assertTrue(events.isEmpty())
            collectorJob.cancel()
        }

    @Test
    fun `register confirmation required emits RegistrationPendingConfirmation event`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.signUpResult = AppResult.Success(SignUpOutcome.CONFIRMATION_REQUIRED)
            val events = mutableListOf<AuthEvent>()
            val collectorJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }

            viewModel.onEmailChange("user@jobradar.vn")
            viewModel.onPasswordChange("secret123")
            viewModel.onConfirmPasswordChange("secret123")

            viewModel.register()
            advanceUntilIdle()

            assertEquals(1, events.size)
            assertEquals(
                AuthEvent.RegistrationPendingConfirmation(R.string.auth_register_confirm_email),
                events.single(),
            )
            collectorJob.cancel()
        }

    @Test
    fun `register network error maps to network error message resource`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.signUpResult = AppResult.Error(
                code = AuthErrorCodes.NETWORK_ERROR,
                message = "Timeout",
            )
            viewModel.onEmailChange("user@jobradar.vn")
            viewModel.onPasswordChange("secret123")
            viewModel.onConfirmPasswordChange("secret123")

            viewModel.register()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(R.string.auth_error_network, viewModel.uiState.value.generalError)
        }

    @Test
    fun `login ignores duplicate invocation while request is in flight`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            repository.signInDelayGate = gate
            viewModel.onEmailChange("user@jobradar.vn")
            viewModel.onPasswordChange("secret123")

            viewModel.login()
            runCurrent()
            assertTrue(viewModel.uiState.value.isLoading)

            viewModel.login()
            runCurrent()
            assertEquals(1, repository.signInCalls)

            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `register strips invisible zero-width and control characters from email`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.signUpResult = AppResult.Success(SignUpOutcome.SIGNED_IN)
            viewModel.onEmailChange("\uFEFF\u0016tl1133210\u200B@gmail.com\u0008\u007F\u200E")
            viewModel.onPasswordChange("secret123")
            viewModel.onConfirmPasswordChange("secret123")

            assertEquals("tl1133210@gmail.com", viewModel.uiState.value.email)

            viewModel.register()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.emailError)
            assertEquals(1, repository.signUpCalls)
            assertEquals("tl1133210@gmail.com", repository.lastEmail)
        }

    @Test
    fun `register normalizes fullwidth compatibility characters in email`() =
        runTest(mainDispatcherRule.testDispatcher) {
            repository.signUpResult = AppResult.Success(SignUpOutcome.SIGNED_IN)
            viewModel.onEmailChange("tl1133210\uFF20gmail\uFF0Ecom")
            viewModel.onPasswordChange("secret123")
            viewModel.onConfirmPasswordChange("secret123")

            viewModel.register()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.emailError)
            assertEquals(1, repository.signUpCalls)
            assertEquals("tl1133210@gmail.com", repository.lastEmail)
            assertEquals("tl1133210@gmail.com", viewModel.uiState.value.email)
        }
}
