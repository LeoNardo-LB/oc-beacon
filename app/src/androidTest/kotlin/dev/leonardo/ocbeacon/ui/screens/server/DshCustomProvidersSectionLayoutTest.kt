package dev.leonardo.ocbeacon.ui.screens.server

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.leonardo.ocbeacon.HiltComponentActivity
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.DshCredentialStatus
import dev.leonardo.ocbeacon.domain.model.DshProviderDirectoryEntry
import dev.leonardo.ocbeacon.domain.model.DshProviderDirectoryRow
import dev.leonardo.ocbeacon.ui.screens.server.providers.dsh.DshCustomProvidersSection
import org.junit.Rule
import org.junit.Test

/**
 * #324① 崩溃回归布局测试（F1 取证：IllegalStateException infinite maximum
 * height constraints——DSH 服务器提供方页 100% 崩溃，OpenCode 同页正常）。
 *
 * 生产嵌入形态：ServerProvidersScreen 的外层 LazyColumn 以 item { } 承载
 * DshCustomProvidersSection。区块内部因此不得再引入自己的纵向滚动容器
 * （LazyColumn / verticalScroll）——纵向滚动容器在 LazyColumn item 内被以
 * 无限最大高度约束测量，测量期即抛 IllegalStateException。
 *
 * 本测试以同构嵌入（LazyColumn > item > section）守住该约束：修复前该测试
 * 在测量期崩溃（红），修复后正常渲染（绿）。
 */
class DshCustomProvidersSectionLayoutTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    private fun entry(provider: String, displayName: String, custom: Boolean = false) =
        DshProviderDirectoryEntry(
            row = DshProviderDirectoryRow(
                provider = provider,
                displayName = displayName,
                active = true,
            ),
            isCustom = custom,
            credential = DshCredentialStatus(
                ref = "ref:$provider",
                configured = true,
                writable = true,
            ),
        )

    /** 复刻生产嵌入：外层纵向滚动容器（LazyColumn）内 item 承载区块。 */
    private fun setSectionContent(directory: List<DshProviderDirectoryEntry>) {
        composeTestRule.setContent {
            MaterialTheme {
                LazyColumn {
                    item {
                        DshCustomProvidersSection(
                            directory = directory,
                            loading = false,
                            settingsBlocked = false,
                            error = null,
                            onRefresh = {},
                            onDiscover = { _, _ -> Result.success(emptyList()) },
                            onCreate = { _, onDone -> onDone(true, null) },
                            onDelete = { _, onDone -> onDone(true, null) },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun directoryRows_renderInsideScrollableParentWithoutCrash() {
        setSectionContent(
            listOf(
                entry("anthropic", "Anthropic"),
                entry("llm-pi-ai/pi", "Custom PI", custom = true),
            )
        )
        composeTestRule.onNodeWithText("Anthropic").assertExists()
        composeTestRule.onNodeWithText("Custom PI").assertExists()
    }

    @Test
    fun emptyDirectory_rendersEmptyHintInsideScrollableParent() {
        setSectionContent(emptyList())
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.dsh_providers_empty))
            .assertExists()
    }
}
