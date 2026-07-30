package dev.leonardo.ocremoteplus.ui.screens.sessions

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * Route wrapper for SessionListScreen.
 * Extracts navigation parameters from SavedStateHandle via ViewModel
 * and binds ViewModel to the composable.
 */
@Composable
fun SessionListRoute(
    onNavigateToChat: (sessionId: String, openTerminal: Boolean) -> Unit,
    onNavigateToNewChat: (directory: String) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToFavorites: () -> Unit = {},
) {
    val viewModel: SessionListViewModel = hiltViewModel()
    SessionListScreen(
        viewModel = viewModel,
        onNavigateToChat = onNavigateToChat,
        onNavigateToNewChat = onNavigateToNewChat,
        onNavigateBack = onNavigateBack,
        onNavigateToFavorites = onNavigateToFavorites,
    )
}
