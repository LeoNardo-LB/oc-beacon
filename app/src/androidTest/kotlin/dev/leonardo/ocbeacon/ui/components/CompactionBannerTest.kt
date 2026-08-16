package dev.leonardo.ocbeacon.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.leonardo.ocbeacon.HiltComponentActivity
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.ui.screens.chat.components.CompactionBanner
import org.junit.Rule
import org.junit.Test

class CompactionBannerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

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
        composeTestRule.onNodeWithText("Compressing context…").assertIsDisplayed()
    }

    @Test
    fun doesNotShowWhenInactive() {
        composeTestRule.setContent {
            CompactionBanner(
                state = CompactionStateInfo(isActive = false)
            )
        }
        composeTestRule.onAllNodesWithText("Compressing context…").assertCountEquals(0)
    }
}
