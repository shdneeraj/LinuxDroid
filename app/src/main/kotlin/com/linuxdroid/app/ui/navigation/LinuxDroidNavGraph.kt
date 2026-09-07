package com.linuxdroid.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.linuxdroid.app.ui.screens.*

/**
 * Top-level navigation destinations.
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Environments : Screen("environments")
    object Terminal : Screen("terminal/{environmentId}") {
        fun route(environmentId: String) = "terminal/$environmentId"
    }
    object Desktop : Screen("desktop/{environmentId}") {
        fun route(environmentId: String) = "desktop/$environmentId"
    }
    object Settings : Screen("settings")
    object Diagnostics : Screen("diagnostics")
    object About : Screen("about")
    object GuiInstaller : Screen("gui_installer/{environmentId}") {
        fun route(environmentId: String) = "gui_installer/$environmentId"
    }
    object PackageManager : Screen("package_manager/{environmentId}") {
        fun route(environmentId: String) = "package_manager/$environmentId"
    }
}

/**
 * Top-level navigation graph for LinuxDroid.
 */
@Composable
fun LinuxDroidNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
    ) {
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        composable(Screen.Environments.route) {
            EnvironmentListScreen(navController = navController)
        }
        composable(
            route = Screen.Terminal.route,
            arguments = listOf(navArgument("environmentId") { type = NavType.StringType })
        ) {
            TerminalScreen(navController = navController)
        }
        composable(
            route = Screen.Desktop.route,
            arguments = listOf(navArgument("environmentId") { type = NavType.StringType })
        ) {
            DesktopScreen(navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable(Screen.Diagnostics.route) {
            DiagnosticsScreen(navController = navController)
        }
        composable(Screen.About.route) {
            AboutScreen(navController = navController)
        }
        composable(
            route = Screen.GuiInstaller.route,
            arguments = listOf(navArgument("environmentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val envId = backStackEntry.arguments?.getString("environmentId") ?: ""
            GuiInstallerScreen(environmentId = envId, navController = navController)
        }
        composable(
            route = Screen.PackageManager.route,
            arguments = listOf(navArgument("environmentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val envId = backStackEntry.arguments?.getString("environmentId") ?: ""
            PackageManagerScreen(environmentId = envId, navController = navController)
        }
    }
}
