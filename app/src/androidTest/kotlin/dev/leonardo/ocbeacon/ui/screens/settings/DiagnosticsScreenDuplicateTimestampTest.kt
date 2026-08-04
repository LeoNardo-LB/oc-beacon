package dev.leonardo.ocbeacon.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocbeacon.HiltComponentActivity
import dev.leonardo.ocbeacon.data.repository.DiagnosticLogEntry
import dev.leonardo.ocbeacon.data.repository.DiagnosticLogRepository
import dev.leonardo.ocbeacon.ui.theme.OpenCodeTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * 回归测试：同一毫秒内产生多条日志（timestamp 相同）时，
 * DiagnosticsScreen 的 LazyColumn 不得因重复 key 崩溃。
 *
 * 复现场景：崩溃报告中 `Key "1785566688405" was already used` ——
 * 该 key 正是日志条目的 timestamp（13 位 epoch 毫秒）。旧代码
 * `items(filteredEntries, key = { it.timestamp })` 在同毫秒两条日志
 * 时抛 IllegalArgumentException。修复后 key 追加列表 index 保证唯一。
 */
@HiltAndroidTest
class DiagnosticsScreenDuplicateTimestampTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltComponentActivity>()

    @Inject
    lateinit var repository: DiagnosticLogRepository

    @Before
    fun setup() {
        hiltRule.inject()
        runBlocking { repository.clear() }
    }

    @After
    fun teardown() {
        runBlocking { repository.clear() }
    }

    @Test
    fun duplicate_timestamp_entries_render_without_crash() {
        // 直接注入两条相同 timestamp 的日志，绕过 AppLogger 的单调化，
        // 模拟修复前已写入数据库的重复数据（或极端竞态场景）。
        val sharedTimestamp = 1_785_566_688_405L // 崩溃报告中的 key
        runBlocking {
            repository.recordBatch(
                listOf(
                    DiagnosticLogEntry(
                        timestamp = sharedTimestamp,
                        level = "ERROR",
                        category = "Test",
                        message = "first duplicate entry",
                    ),
                    DiagnosticLogEntry(
                        timestamp = sharedTimestamp,
                        level = "ERROR",
                        category = "Test",
                        message = "second duplicate entry",
                    ),
                ),
            )
        }

        composeRule.setContent {
            OpenCodeTheme {
                DiagnosticsScreen(onNavigateBack = {})
            }
        }
        composeRule.waitForIdle()

        // 修复前：渲染时抛 IllegalArgumentException（Key was already used）
        // 修复后：两条条目都应正常显示，且均可独立展开。
        composeRule.onNodeWithText("first duplicate entry").assertIsDisplayed()
        composeRule.onNodeWithText("second duplicate entry").assertIsDisplayed()
    }
}
