package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #155 Q11：提示音纯查询路径不得污染通知去重 map——
 * 场景：会话内已响过提示音（compute），用户离场后同 turn 的通知（check）不得被吞。
 */
class FeedbackDedupIsolationTest {

    private lateinit var manager: AppNotificationManager

@Before
    fun setup() {
        val dispatcher = mockk<EventDispatcher>()
        val assistant = Message.Assistant(
            id = "msg_a",
            sessionId = "sess1",
            time = TimeInfo(created = 0),
            parentId = "",
        )
        every { dispatcher.messages } returns MutableStateFlow(
            mapOf("sess1" to listOf(assistant))
        )
        every { dispatcher.parts } returns MutableStateFlow(
            mapOf("msg_a" to listOf(Part.Text(id = "p1", sessionId = "sess1", messageId = "msg_a", text = "done")))
        )
        every { dispatcher.sessions } returns MutableStateFlow(emptyList())
        manager = AppNotificationManager(
            eventDispatcher = dispatcher,
            settingsRepository = mockk(),
            sessionFocusHolder = SessionFocusHolder(),
            feedbackPlayer = mockk(relaxed = true),
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    @Test
    fun computeDoesNotPolluteNotificationDedup() {
        // 提示音路径：纯查询（会话内响过一声）
        assertNotNull(manager.computeNewAssistantMessageId("sess1"))
        // 用户离场 → 通知路径：同一 turn 不得被提示音的去重吞掉
        assertEquals("msg_a", manager.checkNewAssistantMessage("server1", "sess1"))
        // 通知自身的去重仍正常（第二次 check 返回 null）
        assertNull(manager.checkNewAssistantMessage("server1", "sess1"))
    }
}
