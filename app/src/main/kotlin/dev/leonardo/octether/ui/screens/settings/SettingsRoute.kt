package dev.leonardo.octether.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun SettingsRoute(
    onNavigateBack: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    SettingsScreen(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onNavigateToDiagnostics = onNavigateToDiagnostics,
    )
}
