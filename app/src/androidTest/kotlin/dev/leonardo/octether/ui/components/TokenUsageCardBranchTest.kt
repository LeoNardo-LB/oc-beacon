package dev.leonardo.octether.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.octether.ui.screens.chat.components.TokenUsageCard
import org.junit.Rule
import org.junit.Test

class TokenUsageCardBranchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun allTokensZero_rendersWithoutCrash() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 0, outputTokens = 0, reasoningTokens = 0,
                cacheReadTokens = 0, cacheWriteTokens = 0,
                totalCost = 0.0
            )
        }
        composeTestRule.onNodeWithText("0 tokens").assertIsDisplayed()
    }

    @Test
    fun onlyInputTokens_showsCorrectTotal() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 1000, outputTokens = 0, reasoningTokens = 0,
                cacheReadTokens = 0, cacheWriteTokens = 0,
                totalCost = 0.0
            )
        }
        composeTestRule.onNodeWithText("1,000 tokens").assertIsDisplayed()
        composeTestRule.onNodeWithText("Input").assertIsDisplayed()
    }

    @Test
    fun allTokensPositive_showsAllRows() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 1000, outputTokens = 500, reasoningTokens = 200,
                cacheReadTokens = 300, cacheWriteTokens = 100,
                totalCost = 0.05
            )
        }
        composeTestRule.onNodeWithText("1,700 tokens").assertIsDisplayed()
        composeTestRule.onNodeWithText("$0.0500").assertIsDisplayed()
        composeTestRule.onNodeWithText("Input").assertIsDisplayed()
        composeTestRule.onNodeWithText("Output").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reasoning").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cache read").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cache write").assertIsDisplayed()
    }

    @Test
    fun zeroCost_doesNotShowCost() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 100, outputTokens = 0, reasoningTokens = 0,
                cacheReadTokens = 0, cacheWriteTokens = 0,
                totalCost = 0.0
            )
        }
        composeTestRule.onAllNodesWithText("$").assertCountEquals(0)
    }

    @Test
    fun verySmallCost_showsFormatted() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 100, outputTokens = 0, reasoningTokens = 0,
                cacheReadTokens = 0, cacheWriteTokens = 0,
                totalCost = 0.0001
            )
        }
        composeTestRule.onNodeWithText("$0.0001").assertIsDisplayed()
    }

    @Test
    fun largeCost_showsFormatted() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 100, outputTokens = 0, reasoningTokens = 0,
                cacheReadTokens = 0, cacheWriteTokens = 0,
                totalCost = 999.99
            )
        }
        composeTestRule.onNodeWithText("$999.9900").assertIsDisplayed()
    }

    @Test
    fun zeroContextWindow_noProgressBar() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 1000, outputTokens = 500, reasoningTokens = 0,
                cacheReadTokens = 0, cacheWriteTokens = 0,
                totalCost = 0.01
            )
        }
        composeTestRule.onAllNodesWithText("128,000 context").assertCountEquals(0)
    }

    @Test
    fun fiftyPercentUsage_showsContextInfo() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 32000, outputTokens = 32000, reasoningTokens = 0,
                cacheReadTokens = 0, cacheWriteTokens = 0,
                totalCost = 0.0
            )
        }
        composeTestRule.onNodeWithText("64,000 / 128,000 context").assertIsDisplayed()
    }

    @Test
    fun nearFullUsage_showsContextInfo() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 63000, outputTokens = 63000, reasoningTokens = 0,
                cacheReadTokens = 0, cacheWriteTokens = 0,
                totalCost = 0.0
            )
        }
        composeTestRule.onNodeWithText("126,000 / 128,000 context").assertIsDisplayed()
    }

    @Test
    fun over100PercentUsage_showsContextInfo() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 100000, outputTokens = 50000, reasoningTokens = 0,
                cacheReadTokens = 0, cacheWriteTokens = 0,
                totalCost = 0.0
            )
        }
        composeTestRule.onNodeWithText("150,000 / 128,000 context").assertIsDisplayed()
    }

    @Test
    fun allCacheZero_hidesReasoningAndCacheRows() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 1000, outputTokens = 500, reasoningTokens = 0,
                cacheReadTokens = 0, cacheWriteTokens = 0,
                totalCost = 0.0
            )
        }
        composeTestRule.onAllNodesWithText("Reasoning").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Cache read").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Cache write").assertCountEquals(0)
    }
}
