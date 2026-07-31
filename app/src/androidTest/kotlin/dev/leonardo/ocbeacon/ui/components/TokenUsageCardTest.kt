package dev.leonardo.ocbeacon.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.ocbeacon.ui.screens.chat.components.TokenUsageCard
import org.junit.Rule
import org.junit.Test

class TokenUsageCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsTokenUsage() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 1000,
                outputTokens = 500,
                reasoningTokens = 200,
                cacheReadTokens = 0,
                cacheWriteTokens = 0,
                totalCost = 0.05
            )
        }
        composeTestRule.onNodeWithText("1,000").assertIsDisplayed()
    }

    @Test
    fun showsZeroTokens() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 0,
                outputTokens = 0,
                reasoningTokens = 0,
                cacheReadTokens = 0,
                cacheWriteTokens = 0,
                totalCost = 0.0
            )
        }
        // Should render without crashing even with zero values.
        // Multiple "0" nodes exist (input, output, total), so verify at least one is displayed.
        composeTestRule.onAllNodesWithText("0").assertCountEquals(2).fetchSemanticsNodes().let {
            assert(it.isNotEmpty()) { "Expected at least one node with text '0' to be displayed" }
        }
    }
}
