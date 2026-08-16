package dev.leonardo.ocbeacon.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.leonardo.ocbeacon.HiltComponentActivity
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class SessionRetryCardTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Test
    fun showsAttemptNumber() {
        composeTestRule.setContent {
            SessionRetryCard(
                attempt = 2,
                maxAttempts = 3,
                countdownSeconds = null,
                errorMessage = null
            )
        }
        composeTestRule.onNodeWithText("Attempt 2 of 3").assertIsDisplayed()
    }

    @Test
    fun showsErrorMessage() {
        composeTestRule.setContent {
            SessionRetryCard(
                attempt = 1,
                maxAttempts = 3,
                countdownSeconds = null,
                errorMessage = "Connection refused"
            )
        }
        composeTestRule.onNodeWithText("Connection refused").assertIsDisplayed()
    }

    @Test
    fun showsCountdownWhenProvided() {
        composeTestRule.setContent {
            SessionRetryCard(
                attempt = 1,
                maxAttempts = 3,
                countdownSeconds = 10,
                errorMessage = null
            )
        }
        composeTestRule.onNodeWithText("10s").assertIsDisplayed()
    }
}
