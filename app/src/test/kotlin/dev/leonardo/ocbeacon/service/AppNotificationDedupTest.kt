package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import dev.leonardo.ocbeacon.domain.model.Session
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 通知去重键（serverId::sessionId）与抑制（shouldSuppressEvent）的纯函数测试。
 * 修复点：sessionId 是服务器内部 ID，不同服务器可能相同——
 * 去重 key 必须包含 serverId，否则跨服务器会误去重漏通知。
 */
class AppNotificationDedupTest {

    private lateinit var manager: AppNotificationManager
    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var settingsDataStore: SettingsDataStore

    private fun buildManager(holder: SessionFocusHolder = SessionFocusHolder()): AppNotificationManager {
        return AppNotificationManager(
            eventDispatcher,
            settingsDataStore,
            holder,
            mockk(relaxed = true),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    @Before
    fun setup() {
        eventDispatcher = mockk()
        settingsDataStore = mockk()
        every { eventDispatcher.messages } returns MutableStateFlow(emptyMap())
        every { eventDispatcher.parts } returns MutableStateFlow(emptyMap())
        every { eventDispatcher.sessions } returns MutableStateFlow<List<Session>>(emptyList())
        manager = buildManager()
    }

    // ============ 权限通知去重 ============

    @Test
    fun `first permission notification should notify`() {
        assertTrue(manager.shouldNotifyPermission("server1", "session1", "fs.write"))
    }

    @Test
    fun `same server session same permission deduplicated after mark`() {
        assertTrue(manager.shouldNotifyPermission("server1", "session1", "fs.write"))
        manager.markPermissionNotified("server1", "session1", "fs.write")
        assertFalse(manager.shouldNotifyPermission("server1", "session1", "fs.write"))
    }

    @Test
    fun `same server same session different permission both notified`() {
        manager.markPermissionNotified("server1", "session1", "fs.write")
        assertFalse(manager.shouldNotifyPermission("server1", "session1", "fs.write"))
        assertTrue(manager.shouldNotifyPermission("server1", "session1", "bash.exec"))
    }

    @Test
    fun `cross server same session id not deduplicated`() {
        // 回归：修复前去重 key 只用 sessionId，服务器 A/B 同 sessionId 会互相误去重
        manager.markPermissionNotified("server1", "session1", "fs.write")
        assertFalse(manager.shouldNotifyPermission("server1", "session1", "fs.write"))
        assertTrue(manager.shouldNotifyPermission("server2", "session1", "fs.write"))
    }

    @Test
    fun `cancel resets dedup allowing re-notification`() {
        manager.markPermissionNotified("server1", "session1", "fs.write")
        assertFalse(manager.shouldNotifyPermission("server1", "session1", "fs.write"))
        // 模拟 cancelSessionNotifications 内部重置（同一 key 路径）
        manager.markPermissionNotified("server1", "session1", "")
        assertTrue(manager.shouldNotifyPermission("server1", "session1", "fs.write"))
    }

    // ============ 问题通知去重 ============

    @Test
    fun `question notification deduplicated per server session`() {
        assertTrue(manager.shouldNotifyQuestion("server1", "session1", "confirm?"))
        manager.markQuestionNotified("server1", "session1", "confirm?")
        assertFalse(manager.shouldNotifyQuestion("server1", "session1", "confirm?"))
    }

    @Test
    fun `cross server question not deduplicated`() {
        manager.markQuestionNotified("server1", "session1", "confirm?")
        assertFalse(manager.shouldNotifyQuestion("server1", "session1", "confirm?"))
        assertTrue(manager.shouldNotifyQuestion("server2", "session1", "confirm?"))
    }

    // ============ 事件抑制（焦点会话） ============

    @Test
    fun `focused session suppresses permission notification`() {
        val holder = SessionFocusHolder()
        holder.setAppInForeground(true)
        holder.setActiveFocus("server1", "session1")
        val focused = buildManager(holder)

        assertFalse(focused.shouldNotifyPermission("server1", "session1", "fs.write"))
    }

    @Test
    fun `focused session NOT suppressed when app in background`() {
        val holder = SessionFocusHolder()
        holder.setAppInForeground(false)
        holder.setActiveFocus("server1", "session1")
        val focused = buildManager(holder)

        // 2026-08-16 语义修正（通知 P1）：后台不抑制——用户看不到界面，
        // 权限通知必须发出（旧行为静默吞通知，可能错过权限请求）
        assertTrue(focused.shouldNotifyPermission("server1", "session1", "fs.write"))
    }

    @Test
    fun `non focused session not suppressed`() {
        val holder = SessionFocusHolder()
        holder.setAppInForeground(true)
        holder.setActiveFocus("server1", "session1")
        val focused = buildManager(holder)

        assertTrue(focused.shouldNotifyPermission("server1", "session2", "fs.write"))
        assertTrue(focused.shouldNotifyPermission("server2", "session1", "fs.write"))
    }

    @Test
    fun `focused session question also suppressed in foreground`() {
        val holder = SessionFocusHolder()
        // 2026-08-16：补前台状态（新语义仅前台聚焦时抑制）
        holder.setAppInForeground(true)
        holder.setActiveFocus("server1", "session1")
        val focused = buildManager(holder)

        assertFalse(focused.shouldNotifyQuestion("server1", "session1", "confirm?"))
    }
}
