package dev.leonardo.ocbeacon.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import dev.leonardo.ocbeacon.ui.screens.chat.components.ToolProgressCard
import org.junit.Rule
import org.junit.Test

class ToolProgressCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsToolName() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1",
                    partId = "p1",
                    tool = "bash",
                    status = "started"
                )
            )
        }
        composeTestRule.onNodeWithText("bash").assertIsDisplayed()
    }

    @Test
    fun showsTitleWhenProvided() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1",
                    partId = "p1",
                    tool = "bash",
                    status = "running",
                    title = "Running tests"
                )
            )
        }
        composeTestRule.onNodeWithText("Running tests").assertIsDisplayed()
    }

    @Test
    fun showsProgressWhenProvided() {
        composeTestRule.setContent {
            ToolProgressCard(
                toolInfo = ToolProgressInfo(
                    callId = "c1",
                    partId = "p1",
                    tool = "bash",
                    status = "running",
                    progress = "50%"
                )
            )
        }
        composeTestRule.onNodeWithText("50%").assertIsDisplayed()
    }
}
