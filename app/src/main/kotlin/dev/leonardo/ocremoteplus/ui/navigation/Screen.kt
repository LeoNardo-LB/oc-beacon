package dev.leonardo.ocremoteplus.ui.navigation

/**
 * Navigation route constants.
 * Route creation and argument parsing are handled by Nav objects in ui/navigation/routes/.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object WebView : Screen("webview")
    data object SessionList : Screen("sessions")
    data object Chat : Screen("chat")
    data object ServerSettings : Screen("server_settings")
    data object ServerProviders : Screen("server_providers")
    data object ServerModelFilter : Screen("server_model_filter")
    data object Settings : Screen("settings")
    data object About : Screen("about")
    data object Workspace : Screen("workspace")
    data object FileViewer : Screen("file_viewer")
    data object CrossServerFavorites : Screen("cross_favorites")
}
