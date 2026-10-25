package dev.alore.argos.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.alore.argos.ui.server.ServerFormScreen
import dev.alore.argos.ui.sessions.SessionsScreen
import dev.alore.argos.ui.sessions.SessionsViewModel
import dev.alore.argos.ui.settings.SettingsScreen
import dev.alore.argos.ui.terminal.TerminalScreen

/** Pops only if [entry] is still the resumed destination (a double tap must not pop Home too). */
private fun NavController.popFrom(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) popBackStack()
}

@Composable
fun ArgosNavHost() {
    val navController = rememberNavController()
    // Activity-scoped: the sessions list and the server form share it (and its connections).
    val activity = LocalActivity.current as ComponentActivity
    val sessionsViewModel: SessionsViewModel = viewModel(viewModelStoreOwner = activity)

    NavHost(navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            SessionsScreen(
                viewModel = sessionsViewModel,
                onOpenSession = { serverId, name -> navController.navigate(TerminalRoute(serverId, name)) },
                onAddServer = { navController.navigate(ServerFormRoute()) },
                onEditServer = { navController.navigate(ServerFormRoute(it)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<ServerFormRoute> { entry ->
            ServerFormScreen(
                serverId = entry.toRoute<ServerFormRoute>().serverId,
                viewModel = sessionsViewModel,
                onDone = { navController.popFrom(entry) },
            )
        }
        composable<TerminalRoute> { entry ->
            TerminalScreen(onBack = { navController.popFrom(entry) })
        }
        composable<SettingsRoute> { entry ->
            SettingsScreen(onBack = { navController.popFrom(entry) })
        }
    }
}
