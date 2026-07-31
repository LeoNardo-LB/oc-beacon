package dev.leonardo.ocbeacon.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import org.junit.Rule
import org.junit.Test

class ConnectionErrorScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsServerNameAndStatusMessage() {
        composeTestRule.setContent {
            ConnectionErrorScreen(
                serverName = "Test Server",
                statusMessage = "Unable to connect",
                retryCountdown = 0,
                otherServers = emptyList(),
                onRetryClick = {},
                onSwitchServer = {}
            )
        }
        composeTestRule.onNodeWithText("Test Server").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unable to connect").assertIsDisplayed()
    }

    @Test
    fun showsRetryButtonWhenCountdownIsZero() {
        var retryClicked = false
        composeTestRule.setContent {
            ConnectionErrorScreen(
                serverName = "Server",
                statusMessage = "Connection failed",
                retryCountdown = 0,
                otherServers = emptyList(),
                onRetryClick = { retryClicked = true },
                onSwitchServer = {}
            )
        }
        composeTestRule.onNodeWithText("Retry").performClick()
        assert(retryClicked)
    }

    @Test
    fun showsCountdownWhenCountdownIsNonZero() {
        composeTestRule.setContent {
            ConnectionErrorScreen(
                serverName = "Server",
                statusMessage = "Connection failed",
                retryCountdown = 5,
                otherServers = emptyList(),
                onRetryClick = {},
                onSwitchServer = {}
            )
        }
        composeTestRule.onNodeWithText("Retrying in", substring = true).assertIsDisplayed()
    }
}
