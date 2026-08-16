package dev.leonardo.ocbeacon.ui.chat

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.leonardo.ocbeacon.HiltComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.material3.Surface
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.ui.screens.chat.SubagentSummary
import dev.leonardo.ocbeacon.ui.screens.chat.TaskSheet
import dev.leonardo.ocbeacon.ui.screens.chat.TaskUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 2026-08-16：TaskSheet subagent 列表项点击跳转回归测试。
 *
 * 背景：模拟器 uiautomator 点击 item（坐标正确、clickable 容器在场）
 * 探针 0 触发——用 Compose 测试框架的语义级 performClick（不经坐标系）
 * 确定性判断 clickable 是否接线。若本测试通过而真机点击仍失灵，
 * 则问题在坐标/输入层（uiautomator/显示缩放），非 App 代码。
 */
class TaskSheetClickTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Test
    fun subagentItem_click_invokesOpenSubSession() {
        var clickedSessionId: String? = null
        val state = TaskUiState(
            shells = emptyList(),
            subagents = listOf(
                SubagentSummary(
                    sessionId = "ses_test_child_1",
                    agent = "general-fast",
                    title = "写 50 字月亮故事",
                    isRunning = true,
                )
            ),
        )
        composeTestRule.setContent {
            Surface {
                TaskSheet(
                    state = state,
                    onDismiss = {},
                    onOpenSubSession = { sid -> clickedSessionId = sid },
                    onRemoveShell = {},
                    showRunningFilter = true,
                    shellOutputProvider = { "" },
                )
            }
        }
        // isRunning=true 使 item 直接出现在默认 Running 视图（点击语义与
        // History 视图完全相同——同一 ListItem clickable）
        composeTestRule.onNodeWithText("写 50 字月亮故事").performClick()
        composeTestRule.waitForIdle()
        assertTrue("onOpenSubSession 应被调用（语义级点击）", clickedSessionId == "ses_test_child_1")
    }
}
