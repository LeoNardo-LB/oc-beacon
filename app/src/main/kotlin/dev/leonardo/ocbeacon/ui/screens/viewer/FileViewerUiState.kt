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
    // Phase 4: pagination replaces Phase 1 isTruncated
    val totalLineCount: Int = 0,
    val visibleLineCount: Int = 0,
    val isFullyLoaded: Boolean = false,
    val isExtremelyLarge: Boolean = false,
    val diff: VcsFileDiff? = null,
    val hunks: List<DiffHunk> = emptyList(),
    val currentHunkIndex: Int = 0,
    // Phase 2: Markdown render toggle (now multi-format via FileType)
    val renderMode: FileViewerRenderMode = FileViewerRenderMode.SOURCE,
    val fileType: FileType = FileType.TEXT,
    // Phase 2 Task 9: Tool snapshot
    val isToolSnapshot: Boolean = false,
    val toolSnapshotBefore: String? = null,
    val toolSnapshotAfter: String? = null,
    val toolSnapshotContent: String? = null,
    // Phase 3: Annotation state
    val annotations: List<Annotation> = emptyList(),
    // Scroll to this line on initial load (-1 = no scroll, for Edit tool jump)
    val initialScrollLine: Int = -1
) {
    /** Backward-compatible accessor for markdown check. */
    val isMarkdown: Boolean get() = fileType == FileType.MARKDOWN
}
