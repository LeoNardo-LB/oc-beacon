package dev.leonardo.ocbeacon.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.leonardo.ocbeacon.HiltComponentActivity
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.ocbeacon.ui.screens.chat.components.MessageMetaInfo
import org.junit.Rule
import org.junit.Test

class MessageMetaInfoTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Test
    fun showsModelName() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = "claude-4-sonnet",
                durationMs = null,
                inputTokens = null,
                outputTokens = null
            )
        }
        composeTestRule.onNodeWithText("claude-4-sonnet").assertIsDisplayed()
    }

    @Test
    fun showsTokenCount() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = null,
                durationMs = null,
                inputTokens = 1000,
                outputTokens = 500
            )
        }
        composeTestRule.onNodeWithText("1500 tokens").assertIsDisplayed()
    }
}
