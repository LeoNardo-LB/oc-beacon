package dev.leonardo.ocbeacon.ui.screens.viewer

import dev.leonardo.ocbeacon.domain.model.Annotation
import dev.leonardo.ocbeacon.domain.model.VcsFileDiff

enum class FileViewerMode { SOURCE, DIFF }

enum class FileViewerRenderMode { SOURCE, RENDER_PREVIEW }

enum class DiffHunkType { ADDED, REMOVED, MODIFIED }

data class DiffHunk(
    val startLine: Int,
    val patchStartLineIndex: Int,
    val type: DiffHunkType,
    val rawPatch: String
)

data class FileViewerUiState(
    val filePath: String = "",
    val directory: String = "",
    val mode: FileViewerMode = FileViewerMode.SOURCE,
    val isLoading: Boolean = true,
    val content: String = "",
    val isBinary: Boolean = false,
    val mimeType: String? = null,
    val error: Int? = null,
    val isEmpty: Boolean = false,
    // Phase 4：分页替代 Phase 1 的 isTruncated
    val totalLineCount: Int = 0,
    val visibleLineCount: Int = 0,
    val isFullyLoaded: Boolean = false,
    val isExtremelyLarge: Boolean = false,
    val diff: VcsFileDiff? = null,
    val hunks: List<DiffHunk> = emptyList(),
    val currentHunkIndex: Int = 0,
    // Phase 2：Markdown 渲染切换（现在通过 FileType 支持多格式）
    val renderMode: FileViewerRenderMode = FileViewerRenderMode.SOURCE,
    val fileType: FileType = FileType.TEXT,
    // Phase 2 任务 9：工具快照
    val isToolSnapshot: Boolean = false,
    val toolSnapshotBefore: String? = null,
    val toolSnapshotAfter: String? = null,
    val toolSnapshotContent: String? = null,
    // Phase 3：批注状态
    val annotations: List<Annotation> = emptyList(),
    // 初始加载时滚动到此行（-1 = 不滚动，用于 Edit 工具跳转）
    val initialScrollLine: Int = -1
) {
    /** 向后兼容的 markdown 判断访问器。 */
    val isMarkdown: Boolean get() = fileType == FileType.MARKDOWN
}
