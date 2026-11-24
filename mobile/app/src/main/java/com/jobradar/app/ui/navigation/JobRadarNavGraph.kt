package com.jobradar.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jobradar.app.domain.model.AuthSessionState
import com.jobradar.app.ui.auth.LoginScreen
import com.jobradar.app.ui.auth.RegisterScreen
import com.jobradar.app.ui.main.MainShellScreen
import com.jobradar.app.ui.matches.detail.MatchDetailScreen
import com.jobradar.app.ui.splash.SplashScreen

@Composable
fun JobRadarNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    sessionViewModel: SessionViewModel = hiltViewModel(),
) {
    val authState by sessionViewModel.authState.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = SplashRoute,
        modifier = modifier,
    ) {
        composable<SplashRoute> {
            SplashScreen()
        }
        composable<LoginRoute> {
            LoginScreen(
                onNavigateToRegister = {
                    navController.navigate(RegisterRoute) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<RegisterRoute> {
            RegisterScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
            )
        }
        composable<MainShellRoute> {
            MainShellScreen(
                onOpenMatchDetail = { jobId, matchId ->
                    navController.navigate(MatchDetailRoute(jobId = jobId, matchId = matchId)) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<MatchDetailRoute> {
            MatchDetailScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
            )
        }
    }

    LaunchedEffect(authState) {
        val currentDestination = navController.currentDestination
        when (authState) {
            AuthSessionState.Loading -> Unit
            is AuthSessionState.Authenticated -> {
                if (currentDestination?.hasRoute<MainShellRoute>() != true) {
                    navController.navigate(MainShellRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            AuthSessionState.Unauthenticated -> {
                val isAlreadyOnAuthScreen =
                    currentDestination?.hasRoute<LoginRoute>() == true ||
                        currentDestination?.hasRoute<RegisterRoute>() == true
                if (!isAlreadyOnAuthScreen) {
                    navController.navigate(LoginRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        }
    }
}
