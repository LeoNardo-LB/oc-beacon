package dev.leonardo.ocbeacon.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
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
 * 时抛 IllegalArgumentException。修复后 key 由内容派生（timestamp +
 * category + message hash，L-11）保证唯一。
 *
 * #214：sharedTimestamp 必须取当前时刻，不能硬编码崩溃报告里的历史
 * timestamp（2026-08-01T06:44:48Z）——LogStore.insert 的 retention
 * prune 会删除 21 天前的 ERROR 条目，设备时钟越过 2026-08-22 边界后
 * 两条条目插入即被清掉（实测 db=0/flow=0），UI 呈空态、断言失败。
 */
@HiltAndroidTest
class DiagnosticsScreenDuplicateTimestampTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<dev.leonardo.ocbeacon.HiltEntryActivity>()

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
        // timestamp 取当前时刻并共享同一值：既保留「同毫秒重复」语义，
        // 又落在 retention 窗口内（见类注释 #214 时间炸弹教训）。
        val sharedTimestamp = System.currentTimeMillis()
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
