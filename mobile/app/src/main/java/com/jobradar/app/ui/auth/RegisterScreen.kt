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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val latestOnNavigateBack by rememberUpdatedState(onNavigateBack)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AuthEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes))
                }

                is AuthEvent.RegistrationPendingConfirmation -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes))
                    latestOnNavigateBack()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        enabled = !uiState.isLoading,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.auth_back_cd),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { innerPadding ->
        RegisterContent(
            uiState = uiState,
            onEmailChange = viewModel::onEmailChange,
            onPasswordChange = viewModel::onPasswordChange,
            onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
            onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
            onRegisterClick = viewModel::register,
            onNavigateToLogin = onNavigateBack,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
fun RegisterContent(
    uiState: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onRegisterClick: () -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val fieldsEnabled = !uiState.isLoading

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AuthHeader(
            title = stringResource(R.string.auth_register_title),
            subtitle = stringResource(R.string.auth_register_subtitle),
        )
        Spacer(modifier = Modifier.height(24.dp))

        EmailField(
            value = uiState.email,
            onValueChange = onEmailChange,
            errorRes = uiState.emailError,
            enabled = fieldsEnabled,
            placeholderRes = null,
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
            imeAction = ImeAction.Next,
            onImeAction = {},
        )
        Spacer(modifier = Modifier.height(12.dp))

        PasswordField(
            value = uiState.confirmPassword,
            onValueChange = onConfirmPasswordChange,
            label = stringResource(R.string.auth_confirm_password_label),
            isVisible = uiState.isPasswordVisible,
            onToggleVisibility = onTogglePasswordVisibility,
            errorRes = uiState.confirmPasswordError,
            enabled = fieldsEnabled,
            imeAction = ImeAction.Done,
            onImeAction = onRegisterClick,
        )

        if (uiState.generalError != null) {
            Spacer(modifier = Modifier.height(14.dp))
            AuthErrorBanner(errorRes = uiState.generalError)
        }

        Spacer(modifier = Modifier.height(22.dp))
        AuthPrimaryButton(
            text = stringResource(R.string.auth_register_button),
            isLoading = uiState.isLoading,
            onClick = onRegisterClick,
        )

        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = onNavigateToLogin,
            enabled = fieldsEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally),
        ) {
            Text(text = stringResource(R.string.auth_go_to_login))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RegisterContentPreview() {
    JobRadarTheme {
        RegisterContent(
            uiState = AuthUiState(email = "newuser@jobradar.vn"),
            onEmailChange = {},
            onPasswordChange = {},
            onConfirmPasswordChange = {},
            onTogglePasswordVisibility = {},
            onRegisterClick = {},
            onNavigateToLogin = {},
        )
    }
}
