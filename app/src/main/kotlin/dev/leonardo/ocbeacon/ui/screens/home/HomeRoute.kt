package dev.leonardo.ocbeacon.ui.screens.home

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * Route wrapper for HomeScreen.
 * Handles ViewModel binding and navigation parameter extraction.
 * NavGraph calls this instead of HomeScreen directly.
 */
@Composable
fun HomeRoute(
    windowSizeClass: WindowSizeClass,
    onNavigateToSessions: (String, String, String, String, String) -> Unit,
    onNavigateToServerSettings: (String, String, String, String, String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val viewModel: HomeViewModel = hiltViewModel()
    HomeScreen(
        windowSizeClass = windowSizeClass,
        viewModel = viewModel,
        onNavigateToSessions = onNavigateToSessions,
        onNavigateToServerSettings = onNavigateToServerSettings,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToAbout = onNavigateToAbout
    )
}
