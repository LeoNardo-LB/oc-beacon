package dev.leonardo.ocbeacon

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule

/**
 * 提供 Compose test rule 的基础 mixin。
 * 任何需要测试 Compose UI 的插桩测试都可使用此接口。
 */
interface ComposeTestRule {
    @get:Rule
    val composeTestRule: androidx.compose.ui.test.junit4.ComposeTestRule
        get() = createComposeRule()
}
