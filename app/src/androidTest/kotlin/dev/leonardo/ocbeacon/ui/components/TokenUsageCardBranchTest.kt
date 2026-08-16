package dev.leonardo.ocbeacon.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.leonardo.ocbeacon.HiltComponentActivity
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.ocbeacon.ui.screens.chat.components.TokenUsageCard
import org.junit.Rule
import org.junit.Test

class TokenUsageCardBranchTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

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

    // 2026-08-16（locale 无关断言）：%,d 分组符是 Locale 敏感的——模拟器
    // 默认 locale 与测试编写时的 en_US 分组格式可能不同，硬编码 "1,700" 会
    // 在非逗号分组 locale（如 de 的 1.700）失败。经 activity.getString 动态构造。
    private fun totalTokensText(n: Long) = composeTestRule.activity.getString(
        dev.leonardo.ocbeacon.R.string.chat_token_usage_total, n)

    @Test
    fun allTokensPositive_showsAllRows() {
        composeTestRule.setContent {
            TokenUsageCard(
                inputTokens = 1000, outputTokens = 500, reasoningTokens = 200,
                cacheReadTokens = 300, cacheWriteTokens = 100,
                totalCost = 0.05
            )
        }
        composeTestRule.onNodeWithText(totalTokensText(2100)).assertIsDisplayed()
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

    // 2026-08-16 移除：幻影 context 断言——TokenUsageCard 组件无 context
    // 窗口参数（该功能在顶栏 ContextInfo 实现，不在卡片内）。#120 半成品
    // 测试从未运行过（androidTest 基建损坏），断言对应不存在的功能。

    // 2026-08-16 移除：幻影 context 断言——TokenUsageCard 组件无 context
    // 窗口参数（该功能在顶栏 ContextInfo 实现，不在卡片内）。#120 半成品
    // 测试从未运行过（androidTest 基建损坏），断言对应不存在的功能。

    // 2026-08-16 移除：幻影 context 断言——TokenUsageCard 组件无 context
    // 窗口参数（该功能在顶栏 ContextInfo 实现，不在卡片内）。#120 半成品
    // 测试从未运行过（androidTest 基建损坏），断言对应不存在的功能。

    // 2026-08-16 移除：幻影 context 断言——TokenUsageCard 组件无 context
    // 窗口参数（该功能在顶栏 ContextInfo 实现，不在卡片内）。#120 半成品
    // 测试从未运行过（androidTest 基建损坏），断言对应不存在的功能。

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
