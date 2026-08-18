package dev.leonardo.ocbeacon.ui.screens.workspace.tree

import androidx.activity.ComponentActivity
import dev.leonardo.ocbeacon.HiltComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.FileType
import dev.leonardo.ocbeacon.ui.screens.workspace.FileTreeNode
import dev.leonardo.ocbeacon.ui.screens.workspace.WorkspaceUiState
import org.junit.Rule
import org.junit.Test

/**
 * [FileTreePanel] 的插桩测试。验证四种 UI 状态
 * （加载中 / 错误 / 空 / 已填充）以及 showIgnored 过滤器的接线。
 *
 * 使用贴近真实的数据（D7-003）：真实的 OpenCode 文件名和路径。
 */
class FileTreePanelTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Test
    fun loadingState_showsProgressIndicator() {
        composeTestRule.setContent {
            FileTreePanel(
                uiState = WorkspaceUiState(
                    rootLoading = true,
                    rootNodes = emptyList()
                ),
                onRefreshRoot = {},
                onToggleShowIgnored = {},
                onOpenFile = {},
                onToggleExpand = {}
            )
        }
        composeTestRule.onNodeWithTag("file_tree_loading").assertIsDisplayed()
    }

    @Test
    fun errorState_showsErrorMessageAndRetry() {
        composeTestRule.setContent {
            FileTreePanel(
                uiState = WorkspaceUiState(
                    rootLoading = false,
                    rootError = R.string.workspace_error_load_failed,
                    rootNodes = emptyList()
                ),
                onRefreshRoot = {},
                onToggleShowIgnored = {},
                onOpenFile = {},
                onToggleExpand = {}
            )
        }
        composeTestRule.onNodeWithText(
            composeTestRule.activity.getString(R.string.workspace_error_load_failed)
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(dev.leonardo.ocbeacon.R.string.retry)).assertIsDisplayed()
    }

    @Test
    fun emptyState_showsEmptyMessage() {
        composeTestRule.setContent {
            FileTreePanel(
                uiState = WorkspaceUiState(
                    rootLoading = false,
                    rootError = null,
                    rootNodes = emptyList()
                ),
                onRefreshRoot = {},
                onToggleShowIgnored = {},
                onOpenFile = {},
                onToggleExpand = {}
            )
        }
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(dev.leonardo.ocbeacon.R.string.workspace_empty_directory)).assertIsDisplayed()
    }

    @Test
    fun populatedState_showsFileItemsAndHidesIgnored() {
        composeTestRule.setContent {
            FileTreePanel(
                uiState = WorkspaceUiState(
                    rootLoading = false,
                    rootNodes = sampleNodes(),
                    showIgnored = false,
                    // 2026-08-16：子文件仅在目录展开时 flatten（FileTreeUtils 契约）
                    expandedDirs = setOf("app")
                ),
                onRefreshRoot = {},
                onToggleShowIgnored = {},
                onOpenFile = {},
                onToggleExpand = {}
            )
        }
        composeTestRule.onNodeWithText("app").assertIsDisplayed()
        composeTestRule.onNodeWithText("OpenCodeApi.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("build.gradle.kts").assertIsDisplayed()
        // showIgnored = false 时，被忽略的文件被过滤掉
        composeTestRule.onAllNodesWithText(".gitignore").assertCountEquals(0)
    }

    @Test
    fun filterChipClick_invokesToggleCallback() {
        var toggled = false
        composeTestRule.setContent {
            FileTreePanel(
                uiState = WorkspaceUiState(
                    rootLoading = false,
                    rootNodes = sampleNodes(),
                    showIgnored = false,
                    // 2026-08-16：子文件仅在目录展开时 flatten（FileTreeUtils 契约）
                    expandedDirs = setOf("app")
                ),
                onRefreshRoot = {},
                onToggleShowIgnored = { toggled = true },
                onOpenFile = {},
                onToggleExpand = {}
            )
        }
        // 2026-08-18（#149）：原断言 onNodeWithText("显示隐藏") 在 en 测试环境
        // 匹配 0 节点（资源实为"显示忽略项"/"Show ignored"，文案已改测试未跟）
        // → 注入失败。改用 testTag（locale 无关）
        composeTestRule.onNodeWithTag("file_tree_show_ignored").performClick()
        assert(toggled) { "onToggleShowIgnored should be invoked on chip click" }
    }

    /**
     * 模拟 OpenCode 项目布局的真实示例树（D7-003）。
     * 根节点包含目录 `app`（内含两个源文件）和一个被忽略的 `.gitignore`。
     */
    private fun sampleNodes(): List<FileTreeNode> = listOf(
        FileTreeNode(
            node = FileNode(
                name = "app",
                path = "app",
                absolute = "/root/app",
                type = FileType.DIRECTORY,
                ignored = false
            ),
            children = listOf(
                FileTreeNode(
                    node = FileNode(
                        name = "OpenCodeApi.kt",
                        path = "app/OpenCodeApi.kt",
                        absolute = "/root/app/OpenCodeApi.kt",
                        type = FileType.FILE,
                        ignored = false
                    )
                ),
                FileTreeNode(
                    node = FileNode(
                        name = "build.gradle.kts",
                        path = "app/build.gradle.kts",
                        absolute = "/root/app/build.gradle.kts",
                        type = FileType.FILE,
                        ignored = false
                    )
                )
            )
        ),
        FileTreeNode(
            node = FileNode(
                name = ".gitignore",
                path = ".gitignore",
                absolute = "/root/.gitignore",
                type = FileType.FILE,
                ignored = true
            )
        )
    )
}
