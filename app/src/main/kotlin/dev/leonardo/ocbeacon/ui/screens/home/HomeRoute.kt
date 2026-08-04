package dev.leonardo.ocbeacon.ui.screens.home

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * HomeScreen 的路由包装。
 * 处理 ViewModel 绑定和导航参数提取。
 * NavGraph 调用此函数而非直接调用 HomeScreen。
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
