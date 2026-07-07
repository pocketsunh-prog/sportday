package com.sportday.mobile.ui.navigation

import androidx.compose.runtime.*
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sportday.mobile.ui.screens.*
import kotlinx.coroutines.runBlocking

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Register : Screen("register")
    object Home : Screen("home")
    object EventDetail : Screen("event/{eventId}") {
        fun createRoute(eventId: Long) = "event/$eventId"
    }
    object MyEnrollments : Screen("my_enrollments")
    object Results : Screen("results")
    object Profile : Screen("profile")
}

@Composable
fun SportDayNavHost() {
    val navController = rememberNavController()
    var isLoggedIn by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = if (isLoggedIn) Screen.Home.route else Screen.Login.route
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    isLoggedIn = true
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate(Screen.Register.route)
                }
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = {
                    isLoggedIn = true
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Home.route) {
            EventListScreen(
                onEventClick = { eventId ->
                    navController.navigate(Screen.EventDetail.createRoute(eventId))
                },
                onNavigateToEnrollments = {
                    navController.navigate(Screen.MyEnrollments.route)
                },
                onNavigateToResults = {
                    navController.navigate(Screen.Results.route)
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route)
                },
                onLogout = {
                    isLoggedIn = false
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.EventDetail.route,
            arguments = listOf(navArgument("eventId") { type = NavType.LongType })
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments?.getLong("eventId") ?: return@composable
            EventDetailScreen(
                eventId = eventId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.MyEnrollments.route) {
            MyEnrollmentsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Results.route) {
            ResultsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Profile.route) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onLogout = {
                    isLoggedIn = false
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
