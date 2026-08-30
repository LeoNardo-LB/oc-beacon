package dev.leonardo.ocbeacon.ui.screens.workspace

import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.VcsChange

enum class WorkspacePanel { FILE_TREE, GIT_CHANGES }

data class FileTreeNode(
    val node: FileNode,
    val children: List<FileTreeNode>? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class WorkspaceUiState(
    val currentPanel: WorkspacePanel = WorkspacePanel.FILE_TREE,
    val directory: String = "",
    val rootNodes: List<FileTreeNode> = emptyList(),
    val rootLoading: Boolean = true,
    val rootError: Int? = null,
    val showIgnored: Boolean = false,
    // Phase 4: directory expansion state
    val expandedDirs: Set<String> = emptySet(),
    val loadingDirs: Set<String> = emptySet(),
    val gitChanges: List<VcsChange> = emptyList(),
    val gitLoading: Boolean = false,
    val gitError: Int? = null,
    val isNonGit: Boolean = false,
    val gitChangeCount: Int? = null,
    // Phase 2: Search
    val isSearchMode: Boolean = false,
    val searchQuery: String = "",
    val fileSearchResults: List<String> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: Int? = null,
    val hasSearched: Boolean = false,
    // #276 能力位门控（DSH 全 false）：vcs 面板切换 / 文件搜索入口 / 文件内容打开。
    // 目录树保留（host.listDirectory 存在）。默认 true（配置加载前 permissive）。
    val vcsSupported: Boolean = true,
    val fileSearchSupported: Boolean = true,
    val fileReadSupported: Boolean = true
)

data class DirectoryLoadResult(
    val path: String,
    val nodes: List<FileNode>,
    val error: String?
)
