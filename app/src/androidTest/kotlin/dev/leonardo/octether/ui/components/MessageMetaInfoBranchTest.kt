package dev.leonardo.octether.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.octether.ui.screens.chat.components.MessageMetaInfo
import org.junit.Rule
import org.junit.Test

class MessageMetaInfoBranchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun allParamsNonNull_showsAll() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = "claude-4-sonnet",
                durationMs = 5000L,
                inputTokens = 1000,
                outputTokens = 500
            )
        }
        composeTestRule.onNodeWithText("claude-4-sonnet").assertIsDisplayed()
        composeTestRule.onNodeWithText("5s").assertIsDisplayed()
        composeTestRule.onNodeWithText("1500 tokens").assertIsDisplayed()
    }

    @Test
    fun allParamsNull_showsNothing() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = null,
                durationMs = null,
                inputTokens = null,
                outputTokens = null
            )
        }
        composeTestRule.onAllNodesWithText("claude").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("tokens").assertCountEquals(0)
    }

    @Test
    fun modelNull_othersNonNull_showsDurationAndTokens() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = null,
                durationMs = 2000L,
                inputTokens = 800,
                outputTokens = 200
            )
        }
        composeTestRule.onNodeWithText("2s").assertIsDisplayed()
        composeTestRule.onNodeWithText("1000 tokens").assertIsDisplayed()
    }

    @Test
    fun durationZero_showsZeroMs() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = "gpt-4",
                durationMs = 0L,
                inputTokens = null,
                outputTokens = null
            )
        }
        composeTestRule.onNodeWithText("gpt-4").assertIsDisplayed()
        composeTestRule.onNodeWithText("0ms").assertIsDisplayed()
    }

    @Test
    fun durationOneMs_showsMsFormat() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = null,
                durationMs = 1L,
                inputTokens = null,
                outputTokens = null
            )
        }
        composeTestRule.onNodeWithText("1ms").assertIsDisplayed()
    }

    @Test
    fun durationOneHour_showsMinuteFormat() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = null,
                durationMs = 3600000L,
                inputTokens = null,
                outputTokens = null
            )
        }
        composeTestRule.onNodeWithText("60m 0s").assertIsDisplayed()
    }

    @Test
    fun durationMinuteAndSeconds() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = null,
                durationMs = 90000L,
                inputTokens = null,
                outputTokens = null
            )
        }
        composeTestRule.onNodeWithText("1m 30s").assertIsDisplayed()
    }

    @Test
    fun zeroTokens_showsZeroTokens() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = null,
                durationMs = null,
                inputTokens = 0,
                outputTokens = 0
            )
        }
        composeTestRule.onNodeWithText("0 tokens").assertIsDisplayed()
    }

    @Test
    fun largeTokenCounts_showsFormatted() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = null,
                durationMs = null,
                inputTokens = 999999,
                outputTokens = 999999
            )
        }
        composeTestRule.onNodeWithText("1999998 tokens").assertIsDisplayed()
    }

    @Test
    fun longModelName_isDisplayed() {
        val longModel = "anthropic-claude-4-opus-20250115-v2-extended"
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = longModel,
                durationMs = null,
                inputTokens = null,
                outputTokens = null
            )
        }
        composeTestRule.onNodeWithText(longModel).assertIsDisplayed()
    }

    @Test
    fun durationSubSecond_showsMs() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = null,
                durationMs = 999L,
                inputTokens = null,
                outputTokens = null
            )
        }
        composeTestRule.onNodeWithText("999ms").assertIsDisplayed()
    }

    @Test
    fun durationSeconds_showsSecondFormat() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = null,
                durationMs = 1500L,
                inputTokens = null,
                outputTokens = null
            )
        }
        composeTestRule.onNodeWithText("1s").assertIsDisplayed()
    }

    @Test
    fun inputOnly_outputNull_noTokensShown() {
        composeTestRule.setContent {
            MessageMetaInfo(
                modelName = "claude-4",
                durationMs = null,
                inputTokens = 1000,
                outputTokens = null
            )
        }
        composeTestRule.onNodeWithText("claude-4").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("tokens").assertCountEquals(0)
    }
}
