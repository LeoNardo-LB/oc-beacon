package dev.leonardo.ocbeacon

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule

/**
 * Base mixin providing a Compose test rule.
 * Use this in any instrumented test that needs to test Compose UI.
 */
interface ComposeTestRule {
    @get:Rule
    val composeTestRule: androidx.compose.ui.test.junit4.ComposeTestRule
        get() = createComposeRule()
}
