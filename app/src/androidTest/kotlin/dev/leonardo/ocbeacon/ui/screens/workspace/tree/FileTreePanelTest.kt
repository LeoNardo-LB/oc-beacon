package dev.leonardo.ocbeacon.ui.screens.workspace.tree

import androidx.activity.ComponentActivity
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
 * Instrumented tests for [FileTreePanel]. Verifies the four UI states
 * (loading / error / empty / populated) and the showIgnored filter wiring.
 *
 * Uses realistic sample data (D7-003): real OpenCode file names and paths.
 */
class FileTreePanelTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

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
        composeTestRule.onNodeWithText("重试").assertIsDisplayed()
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
        composeTestRule.onNodeWithText("空目录").assertIsDisplayed()
    }

    @Test
    fun populatedState_showsFileItemsAndHidesIgnored() {
        composeTestRule.setContent {
            FileTreePanel(
                uiState = WorkspaceUiState(
                    rootLoading = false,
                    rootNodes = sampleNodes(),
                    showIgnored = false
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
        // Ignored file is filtered out when showIgnored = false
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
                    showIgnored = false
                ),
                onRefreshRoot = {},
                onToggleShowIgnored = { toggled = true },
                onOpenFile = {},
                onToggleExpand = {}
            )
        }
        composeTestRule.onNodeWithText("显示隐藏").performClick()
        assert(toggled) { "onToggleShowIgnored should be invoked on chip click" }
    }

    /**
     * Realistic sample tree mirroring an OpenCode project layout (D7-003).
     * Root contains a directory `app` (with two source files) and an ignored `.gitignore`.
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
