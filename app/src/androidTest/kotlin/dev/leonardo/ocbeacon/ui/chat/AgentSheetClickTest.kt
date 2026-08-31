package dev.leonardo.ocbeacon.ui.chat

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.leonardo.ocbeacon.HiltComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.material3.Surface
import dev.leonardo.ocbeacon.ui.screens.chat.AgentSheet
import dev.leonardo.ocbeacon.ui.screens.chat.SubagentTreeRow
import dev.leonardo.ocbeacon.ui.screens.chat.TaskUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 2026-08-16：subagent 列表项点击跳转回归测试（原 TaskSheet，2026-08-21 拆分为 AgentSheet；
 * 2026-09 树化后行数据源为 SubagentTreeRow——同一 clickable 语义）。
 *
 * 背景：模拟器 uiautomator 点击 item（坐标正确、clickable 容器在场）
 * 探针 0 触发——用 Compose 测试框架的语义级 performClick（不经坐标系）
 * 确定性判断 clickable 是否接线。若本测试通过而真机点击仍失灵，
 * 则问题在坐标/输入层（uiautomator/显示缩放），非 App 代码。
 */
class AgentSheetClickTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Test
    fun subagentItem_click_invokesOpenSubSession() {
        var clickedSessionId: String? = null
        val state = TaskUiState(
            shells = emptyList(),
            subagentTreeRows = listOf(
                SubagentTreeRow(
                    sessionId = "ses_test_child_1",
                    depth = 0,
                    label = "写 50 字月亮故事",
                    isRunning = true,
                    hasChildren = false,
                )
            ),
        )
        composeTestRule.setContent {
            Surface {
                AgentSheet(
                    state = state,
                    onDismiss = {},
                    onOpenSubSession = { sid -> clickedSessionId = sid },
                )
            }
        }
        // AgentSheet 无过滤视图，item 直接呈现（同一 ListItem clickable 语义）
        composeTestRule.onNodeWithText("写 50 字月亮故事").performClick()
        composeTestRule.waitForIdle()
        assertTrue("onOpenSubSession 应被调用（语义级点击）", clickedSessionId == "ses_test_child_1")
    }
}
