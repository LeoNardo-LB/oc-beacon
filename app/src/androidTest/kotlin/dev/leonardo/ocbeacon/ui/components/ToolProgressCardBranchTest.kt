package dev.leonardo.ocbeacon.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import dev.leonardo.ocbeacon.ui.screens.chat.components.ToolProgressCard
import org.junit.Rule
import org.junit.Test

class ToolProgressCardBranchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun started_showsToolName() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = "bash", status = "started"
                )
            )
        }
        composeTestRule.onNodeWithText("bash").assertIsDisplayed()
    }

    @Test
    fun started_withProgress_showsProgress() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = "bash", status = "started",
                    progress = "50%"
                )
            )
        }
        composeTestRule.onNodeWithText("50%").assertIsDisplayed()
    }

    @Test
    fun running_showsProgressString() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = "bash", status = "running",
                    progress = "75%"
                )
            )
        }
        composeTestRule.onNodeWithText("75%").assertIsDisplayed()
    }

    @Test
    fun running_showsTitleInsteadOfTool() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = "bash", status = "running",
                    title = "Running tests"
                )
            )
        }
        composeTestRule.onNodeWithText("Running tests").assertIsDisplayed()
    }

    @Test
    fun running_withBothTitleAndProgress() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = "bash", status = "running",
                    progress = "30%", title = "Compiling"
                )
            )
        }
        composeTestRule.onNodeWithText("Compiling").assertIsDisplayed()
        composeTestRule.onNodeWithText("30%").assertIsDisplayed()
    }

    @Test
    fun running_minimal_showsToolName() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = "grep", status = "running"
                )
            )
        }
        composeTestRule.onNodeWithText("grep").assertIsDisplayed()
    }

    @Test
    fun running_noTitle_noProgressText() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = "grep", status = "running"
                )
            )
        }
        composeTestRule.onAllNodesWithText("%").assertCountEquals(0)
    }

    @Test
    fun completed_showsToolName() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = "bash", status = "completed"
                )
            )
        }
        composeTestRule.onNodeWithText("bash").assertIsDisplayed()
    }

    @Test
    fun longToolName_isDisplayed() {
        val longName = "extremely_long_tool_name_that_exceeds_normal_width"
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = longName, status = "started"
                )
            )
        }
        composeTestRule.onNodeWithText(longName).assertIsDisplayed()
    }

    @Test
    fun veryLongProgressString_isDisplayed() {
        val longProgress = "Processing file 999 of 1000 (99.9%) - almost done"
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = "bash", status = "running",
                    progress = longProgress
                )
            )
        }
        composeTestRule.onNodeWithText(longProgress).assertIsDisplayed()
    }

    @Test
    fun emptyProgressString_showsToolName() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = "bash", status = "running",
                    progress = ""
                )
            )
        }
        composeTestRule.onNodeWithText("bash").assertIsDisplayed()
    }

    @Test
    fun emptyTitle_fallsBackToEmptyText() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1", partId = "p1",
                    tool = "bash", status = "running",
                    title = ""
                )
            )
        }
        // title="" 不是 null，因此显示 "" 而非 "bash"
        composeTestRule.onAllNodesWithText("bash").assertCountEquals(0)
    }

    @Test
    fun multipleTools_allDisplayed() {
        composeTestRule.setContent {
            val tools = listOf(
                ToolProgressInfo("c1", "p1", "bash", "started"),
                ToolProgressInfo("c2", "p2", "grep", "running", progress = "50%"),
                ToolProgressInfo("c3", "p3", "find", "running", title = "Searching")
            )
            tools.forEach { ToolProgressCard(toolInfo = it) }
        }
        composeTestRule.onNodeWithText("bash").assertIsDisplayed()
        composeTestRule.onNodeWithText("grep").assertIsDisplayed()
        composeTestRule.onNodeWithText("Searching").assertIsDisplayed()
        composeTestRule.onNodeWithText("50%").assertIsDisplayed()
    }
}
