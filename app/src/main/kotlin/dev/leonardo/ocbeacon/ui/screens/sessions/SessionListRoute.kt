package dev.leonardo.ocbeacon.ui.screens.sessions

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * SessionListScreen 的路由包装。
 * 通过 ViewModel 从 SavedStateHandle 提取导航参数，
 * 并将 ViewModel 绑定到 composable。
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
