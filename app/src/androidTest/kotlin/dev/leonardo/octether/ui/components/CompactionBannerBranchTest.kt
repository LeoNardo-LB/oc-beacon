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

class CompactionBannerBranchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun activeWithReason_showsReasonText() {
        composeTestRule.setContent {
            CompactionBanner(
                state = CompactionStateInfo(isActive = true, reason = "Compacting context...")
            )
        }
        composeTestRule.onNodeWithText("Compressing context: Compacting context...").assertIsDisplayed()
    }

    @Test
    fun activeWithEmptyReason_showsDefaultText() {
        composeTestRule.setContent {
            CompactionBanner(
                state = CompactionStateInfo(isActive = true, reason = "")
            )
        }
        composeTestRule.onNodeWithText("Compressing context...").assertIsDisplayed()
    }

    @Test
    fun activeWithBlankReason_showsDefaultText() {
        composeTestRule.setContent {
            CompactionBanner(
                state = CompactionStateInfo(isActive = true, reason = "   ")
            )
        }
        composeTestRule.onNodeWithText("Compressing context...").assertIsDisplayed()
    }

    @Test
    fun activeWithLongReason_showsFullText() {
        val longReason = "Reducing 500k tokens to 100k to stay within limits"
        composeTestRule.setContent {
            CompactionBanner(
                state = CompactionStateInfo(isActive = true, reason = longReason)
            )
        }
        composeTestRule.onNodeWithText("Compressing context: $longReason").assertIsDisplayed()
    }

    @Test
    fun activeWithSpecialChars_showsText() {
        val reason = "context-special_v2"
        composeTestRule.setContent {
            CompactionBanner(
                state = CompactionStateInfo(isActive = true, reason = reason)
            )
        }
        composeTestRule.onNodeWithText("Compressing context: $reason").assertIsDisplayed()
    }

    @Test
    fun inactive_doesNotShowBanner() {
        composeTestRule.setContent {
            CompactionBanner(
                state = CompactionStateInfo(isActive = false)
            )
        }
        composeTestRule.onAllNodesWithText("Compressing context...").assertCountEquals(0)
    }

    @Test
    fun inactiveWithReason_doesNotShow() {
        composeTestRule.setContent {
            CompactionBanner(
                state = CompactionStateInfo(isActive = false, reason = "some reason")
            )
        }
        composeTestRule.onAllNodesWithText("Compressing context: some reason").assertCountEquals(0)
    }
}
