package dev.leonardo.ocbeacon.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.leonardo.ocbeacon.ui.screens.chat.components.CopyButton
import org.junit.Rule
import org.junit.Test

class CopyButtonTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsCopyIcon() {
        composeTestRule.setContent {
            CopyButton(text = "hello world")
        }
        composeTestRule.onNodeWithContentDescription("Copy").assertIsDisplayed()
    }
}
