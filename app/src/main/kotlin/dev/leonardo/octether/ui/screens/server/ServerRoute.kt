package dev.leonardo.octether.ui.screens.server

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun ServerSettingsRoute(
    onNavigateBack: () -> Unit,
    onNavigateToProviders: () -> Unit,
    onNavigateToModelFilter: () -> Unit
) {
    ServerSettingsScreen(
        onNavigateBack = onNavigateBack,
        onOpenProviders = onNavigateToProviders,
        onOpenModels = onNavigateToModelFilter
    )
}

@Composable
fun ServerProvidersRoute(
    onNavigateBack: () -> Unit
) {
    val viewModel: ServerSettingsViewModel = hiltViewModel()
    ServerProvidersScreen(
        onNavigateBack = onNavigateBack,
        viewModel = viewModel
    )
}

@Composable
fun ServerModelFilterRoute(
    onNavigateBack: () -> Unit
) {
    val viewModel: ServerSettingsViewModel = hiltViewModel()
    ServerModelFilterScreen(
        onNavigateBack = onNavigateBack,
        viewModel = viewModel
    )
}
