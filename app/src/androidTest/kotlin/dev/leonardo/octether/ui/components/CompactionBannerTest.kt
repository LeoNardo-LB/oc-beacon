package dev.leonardo.octether.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.octether.domain.model.CompactionStateInfo
import dev.leonardo.octether.ui.screens.chat.components.CompactionBanner
import org.junit.Rule
import org.junit.Test

class CompactionBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsCompressingWhenActive() {
        composeTestRule.setContent {
            CompactionBanner(
                state = CompactionStateInfo(isActive = true, reason = "context full")
            )
        }
        composeTestRule.onNodeWithText("Compressing context: context full").assertIsDisplayed()
    }

    @Test
    fun showsDefaultTextWithoutReason() {
        composeTestRule.setContent {
            CompactionBanner(
                state = CompactionStateInfo(isActive = true)
            )
        }
        composeTestRule.onNodeWithText("Compressing context...").assertIsDisplayed()
    }

    @Test
    fun doesNotShowWhenInactive() {
        composeTestRule.setContent {
            CompactionBanner(
                state = CompactionStateInfo(isActive = false)
            )
        }
        composeTestRule.onAllNodesWithText("Compressing context...").assertCountEquals(0)
    }
}
