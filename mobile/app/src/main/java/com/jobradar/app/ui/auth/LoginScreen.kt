package com.jobradar.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobradar.app.R
import com.jobradar.app.ui.auth.components.AuthErrorBanner
import com.jobradar.app.ui.auth.components.AuthHeader
import com.jobradar.app.ui.auth.components.AuthPrimaryButton
import com.jobradar.app.ui.auth.components.EmailField
import com.jobradar.app.ui.auth.components.PasswordField
import com.jobradar.app.ui.theme.JobRadarTheme

@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AuthEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes))
                }

                is AuthEvent.RegistrationPendingConfirmation -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes))
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        LoginContent(
            uiState = uiState,
            onEmailChange = viewModel::onEmailChange,
            onPasswordChange = viewModel::onPasswordChange,
            onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
            onLoginClick = viewModel::login,
            onNavigateToRegister = onNavigateToRegister,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
fun LoginContent(
    uiState: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onLoginClick: () -> Unit,
    onNavigateToRegister: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val fieldsEnabled = !uiState.isLoading

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AuthHeader(
            title = stringResource(R.string.auth_login_title),
            subtitle = stringResource(R.string.auth_login_subtitle),
        )
        Spacer(modifier = Modifier.height(28.dp))

        EmailField(
            value = uiState.email,
            onValueChange = onEmailChange,
            errorRes = uiState.emailError,
            enabled = fieldsEnabled,
        )
        Spacer(modifier = Modifier.height(12.dp))

        PasswordField(
            value = uiState.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.auth_password_label),
            isVisible = uiState.isPasswordVisible,
            onToggleVisibility = onTogglePasswordVisibility,
            errorRes = uiState.passwordError,
            enabled = fieldsEnabled,
            imeAction = ImeAction.Done,
            onImeAction = onLoginClick,
        )

        if (uiState.generalError != null) {
            Spacer(modifier = Modifier.height(14.dp))
            AuthErrorBanner(errorRes = uiState.generalError)
        }

        Spacer(modifier = Modifier.height(22.dp))
        AuthPrimaryButton(
            text = stringResource(R.string.auth_login_button),
            isLoading = uiState.isLoading,
            onClick = onLoginClick,
        )

        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = onNavigateToRegister,
            enabled = fieldsEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally),
        ) {
            Text(text = stringResource(R.string.auth_go_to_register))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginContentPreview() {
    JobRadarTheme {
        LoginContent(
            uiState = AuthUiState(email = "dev@jobradar.vn"),
            onEmailChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onLoginClick = {},
            onNavigateToRegister = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginContentErrorPreview() {
    JobRadarTheme {
        LoginContent(
            uiState = AuthUiState(
                email = "invalid-email",
                emailError = R.string.auth_error_email_invalid,
                generalError = R.string.auth_error_invalid_credentials,
            ),
            onEmailChange = {},
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onLoginClick = {},
            onNavigateToRegister = {},
        )
    }
}
