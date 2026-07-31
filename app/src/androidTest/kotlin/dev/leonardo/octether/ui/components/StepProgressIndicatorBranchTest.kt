package dev.leonardo.octether.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.octether.domain.model.StepProgressInfo
import dev.leonardo.octether.ui.screens.chat.components.StepProgressIndicator
import org.junit.Rule
import org.junit.Test

class StepProgressIndicatorBranchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun step1_only_showsStepNumber() {
        composeTestRule.setContent {
            StepProgressIndicator(stepInfo = StepProgressInfo(step = 1))
        }
        composeTestRule.onNodeWithText("Step 1").assertIsDisplayed()
    }

    @Test
    fun step1_noAgentOrModel_noExtraText() {
        composeTestRule.setContent {
            StepProgressIndicator(stepInfo = StepProgressInfo(step = 1))
        }
        composeTestRule.onAllNodesWithText("build").assertCountEquals(0)
    }

    @Test
    fun step5_withAgentAndModel_showsAll() {
        composeTestRule.setContent {
            StepProgressIndicator(
                stepInfo = StepProgressInfo(step = 5, agent = "build", model = "gpt-4")
            )
        }
        composeTestRule.onNodeWithText("Step 5").assertIsDisplayed()
        composeTestRule.onNodeWithText("build").assertIsDisplayed()
        composeTestRule.onNodeWithText("gpt-4").assertIsDisplayed()
    }

    @Test
    fun step0_showsStepZero() {
        composeTestRule.setContent {
            StepProgressIndicator(stepInfo = StepProgressInfo(step = 0))
        }
        composeTestRule.onNodeWithText("Step 0").assertIsDisplayed()
    }

    @Test
    fun step1_emptyAgentAndModel_onlyStepShown() {
        composeTestRule.setContent {
            StepProgressIndicator(
                stepInfo = StepProgressInfo(step = 1, agent = "", model = "")
            )
        }
        composeTestRule.onNodeWithText("Step 1").assertIsDisplayed()
    }

    @Test
    fun largeStepNumber_showsCorrectly() {
        composeTestRule.setContent {
            StepProgressIndicator(
                stepInfo = StepProgressInfo(step = 100, agent = "agent-x")
            )
        }
        composeTestRule.onNodeWithText("Step 100").assertIsDisplayed()
        composeTestRule.onNodeWithText("agent-x").assertIsDisplayed()
    }

    @Test
    fun specialCharsInAgent_showsCorrectly() {
        composeTestRule.setContent {
            StepProgressIndicator(
                stepInfo = StepProgressInfo(step = 3, agent = "code-review v2", model = "claude-4")
            )
        }
        composeTestRule.onNodeWithText("Step 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("code-review v2").assertIsDisplayed()
        composeTestRule.onNodeWithText("claude-4").assertIsDisplayed()
    }

    @Test
    fun onlyAgent_noModel() {
        composeTestRule.setContent {
            StepProgressIndicator(
                stepInfo = StepProgressInfo(step = 2, agent = "reviewer")
            )
        }
        composeTestRule.onNodeWithText("reviewer").assertIsDisplayed()
    }

    @Test
    fun noAgent_onlyModel() {
        composeTestRule.setContent {
            StepProgressIndicator(
                stepInfo = StepProgressInfo(step = 4, agent = "", model = "gpt-4o")
            )
        }
        composeTestRule.onNodeWithText("gpt-4o").assertIsDisplayed()
    }

    @Test
    fun whitespaceAgent_isHidden() {
        composeTestRule.setContent {
            StepProgressIndicator(
                stepInfo = StepProgressInfo(step = 1, agent = "   ")
            )
        }
        composeTestRule.onNodeWithText("Step 1").assertIsDisplayed()
    }
}
