package com.jobradar.app.ui.main

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

@Immutable
data class MainShellUiState(
    val email: String? = null,
    val backendStatus: BackendStatus = BackendStatus.Checking,
    val isSigningOut: Boolean = false,
    val latestCompletedJobId: String? = null,
)

sealed interface BackendStatus {
    data object Checking : BackendStatus

    data class Online(val environment: String?) : BackendStatus

    data class Offline(@param:StringRes val messageRes: Int) : BackendStatus
}
