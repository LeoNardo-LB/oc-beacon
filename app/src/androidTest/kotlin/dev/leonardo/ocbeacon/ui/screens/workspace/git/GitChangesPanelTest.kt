package dev.leonardo.ocbeacon.ui.screens.workspace.git

import androidx.activity.ComponentActivity
import dev.leonardo.ocbeacon.HiltComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.VcsChange
import dev.leonardo.ocbeacon.domain.model.VcsStatus
import dev.leonardo.ocbeacon.ui.screens.workspace.WorkspaceUiState
import org.junit.Rule
import org.junit.Test

/**
 * [GitChangesPanel] 的插桩测试。验证带状态徽章的变更渲染、
 * 干净工作树状态，以及错误/重试状态。
 *
 * 使用贴近真实的数据（D7-003）：真实的 OpenCode 文件路径和数量。
 */
class GitChangesPanelTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Test
    fun gitChangesPanel_rendersChangesWithStatusBadges() {
        composeTestRule.setContent {
            GitChangesPanel(
                uiState = WorkspaceUiState(
                    gitLoading = false,
                    gitError = null,
                    isNonGit = false,
                    gitChanges = sampleChanges()
                ),
                onRefresh = {},
                onOpenDiff = {}
            )
        }
        composeTestRule.onNodeWithText("app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("app/build.gradle.kts").assertIsDisplayed()
        composeTestRule.onNodeWithText("README.md").assertIsDisplayed()
        composeTestRule.onNodeWithText("+45 -2").assertIsDisplayed()
        composeTestRule.onNodeWithText("+12 -0").assertIsDisplayed()
        composeTestRule.onNodeWithText("+0 -18").assertIsDisplayed()
    }

    @Test
    fun gitChangesPanel_showsCleanStateWhenNoChanges() {
        composeTestRule.setContent {
            GitChangesPanel(
                uiState = WorkspaceUiState(
                    gitLoading = false,
                    gitError = null,
                    isNonGit = false,
                    gitChanges = emptyList()
                ),
                onRefresh = {},
                onOpenDiff = {}
            )
        }
        composeTestRule.onNodeWithText(
            composeTestRule.activity.getString(R.string.workspace_git_working_tree_clean)
        ).assertIsDisplayed()
    }

    @Test
    fun gitChangesPanel_showsErrorState() {
        composeTestRule.setContent {
            GitChangesPanel(
                uiState = WorkspaceUiState(
                    gitLoading = false,
                    gitError = R.string.workspace_error_load_failed,
                    isNonGit = false,
                    gitChanges = emptyList()
                ),
                onRefresh = {},
                onOpenDiff = {}
            )
        }
        composeTestRule.onNodeWithText(
            composeTestRule.activity.getString(R.string.workspace_error_load_failed)
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            composeTestRule.activity.getString(R.string.workspace_retry)
        ).assertIsDisplayed()
    }

    private fun sampleChanges(): List<VcsChange> = listOf(
        VcsChange(
            file = "app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt",
            additions = 45,
            deletions = 2,
            status = VcsStatus.MODIFIED
        ),
        VcsChange(
            file = "app/build.gradle.kts",
            additions = 12,
            deletions = 0,
            status = VcsStatus.ADDED
        ),
        VcsChange(
            file = "README.md",
            additions = 0,
            deletions = 18,
            status = VcsStatus.DELETED
        )
    )
}
