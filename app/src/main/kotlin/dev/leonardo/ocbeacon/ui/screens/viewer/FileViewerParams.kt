package dev.leonardo.ocbeacon.ui.screens.viewer

/**
 * Parameters for [FileViewerViewModel], replacing the old SavedStateHandle + NavBackStackEntry
 * approach. Passed directly via @AssistedInject, decoupling the ViewModel from the navigation system.
 */
data class FileViewerParams(
    val serverId: String,
    val sessionId: String,
    val filePath: String,
    val directory: String,
    val source: String,
    val toolPartIds: List<String> = emptyList()
)

object FileViewerSource {
    const val LIVE = "live"
    const val GIT_DIFF = "git_diff"
    const val TOOL_SNAPSHOT = "tool_snapshot"
    const val TOOL_SNAPSHOT_DIFF = "tool_snapshot_diff"
}
