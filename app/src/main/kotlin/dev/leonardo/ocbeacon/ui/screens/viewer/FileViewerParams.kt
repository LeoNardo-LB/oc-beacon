package dev.leonardo.ocbeacon.ui.screens.viewer

/**
 * [FileViewerViewModel] 的参数，替代旧的 SavedStateHandle + NavBackStackEntry
 * 方式。通过 @AssistedInject 直接传入，让 ViewModel 与导航系统解耦。
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
