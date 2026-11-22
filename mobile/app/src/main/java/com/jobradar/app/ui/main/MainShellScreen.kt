package com.jobradar.app.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jobradar.app.ui.auth.AuthEvent
import com.jobradar.app.ui.navigation.ProfileTabRoute
import com.jobradar.app.ui.navigation.ScanCvTabRoute
import com.jobradar.app.ui.navigation.SuggestionsTabRoute

@Composable
fun MainShellScreen(
    modifier: Modifier = Modifier,
    viewModel: MainShellViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tabNavController = rememberNavController()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
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
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    val isSelected = currentDestination?.hierarchy?.any {
                        it.hasRoute(tab.route::class)
                    } == true
                    val label = stringResource(tab.labelRes)
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            tabNavController.navigate(tab.route) {
                                popUpTo(tabNavController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = label,
                            )
                        },
                        label = { Text(text = label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = tabNavController,
            startDestination = ScanCvTabRoute,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<ScanCvTabRoute> {
                ScanCvPlaceholder()
            }
            composable<SuggestionsTabRoute> {
                SuggestionsPlaceholder()
            }
            composable<ProfileTabRoute> {
                ProfileTabContent(
                    uiState = uiState,
                    onRetryHealth = viewModel::retryHealthCheck,
                    onSignOut = viewModel::signOut,
                )
            }
        }
    }
}
