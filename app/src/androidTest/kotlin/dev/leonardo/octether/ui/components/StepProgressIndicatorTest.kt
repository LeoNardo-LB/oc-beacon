package dev.leonardo.octether.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.octether.domain.model.StepProgressInfo
import dev.leonardo.octether.ui.screens.chat.components.StepProgressIndicator
import org.junit.Rule
import org.junit.Test

class StepProgressIndicatorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsStepNumber() {
        composeTestRule.setContent {
            StepProgressIndicator(
                stepInfo = StepProgressInfo(step = 1)
            )
        }
        composeTestRule.onNodeWithText("Step 1").assertIsDisplayed()
    }

    @Test
    fun showsAgentName() {
        composeTestRule.setContent {
            StepProgressIndicator(
                stepInfo = StepProgressInfo(step = 2, agent = "code")
            )
        }
        composeTestRule.onNodeWithText("code").assertIsDisplayed()
    }

    @Test
    fun showsModelName() {
        composeTestRule.setContent {
            StepProgressIndicator(
                stepInfo = StepProgressInfo(step = 3, agent = "code", model = "claude-4-sonnet")
            )
        }
        composeTestRule.onNodeWithText("claude-4-sonnet").assertIsDisplayed()
    }
}
