package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FindUserMessagesTest {

    private lateinit var manager: AppNotificationManager
    private val eventDispatcher: EventDispatcher = mockk()
    private val settingsDataStore: SettingsDataStore = mockk()

    @Before
    fun setup() {
        every { eventDispatcher.messages } returns MutableStateFlow(emptyMap())
        every { eventDispatcher.parts } returns MutableStateFlow(emptyMap())
        every { eventDispatcher.sessions } returns MutableStateFlow<List<Session>>(emptyList())
        manager = AppNotificationManager(
            eventDispatcher,
            settingsDataStore,
            SessionFocusHolder(),
            mockk(relaxed = true),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    private fun userMessage(id: String, created: Long): Message.User {
        return Message.User(
            id = id,
            sessionId = "session1",
            role = "user",
            time = TimeInfo(created = created)
        )
    }

    private fun textPart(msgId: String, text: String, synthetic: Boolean? = null): Part.Text {
        return Part.Text(
            id = "part_$msgId",
            sessionId = "session1",
            messageId = msgId,
            text = text,
            synthetic = synthetic
        )
    }

    @Test
    fun `returns empty list when no messages`() {
        val result = manager.findLatestUserMessages("session1", 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty list when no user messages`() {
        every { eventDispatcher.messages.value } returns mapOf(
            "session1" to listOf(
                Message.Assistant(
                    id = "a1", sessionId = "session1", role = "assistant",
                    time = TimeInfo(created = 100), parentId = "u1"
                )
            )
        )
        val result = manager.findLatestUserMessages("session1", 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extracts user message text`() {
        every { eventDispatcher.messages.value } returns mapOf(
            "session1" to listOf(userMessage("u1", 100))
        )
        every { eventDispatcher.parts.value } returns mapOf(
            "u1" to listOf(textPart("u1", "Hello world"))
        )
        val result = manager.findLatestUserMessages("session1", 5)
        assertEquals(1, result.size)
        assertEquals("Hello world", result[0].text)
        assertEquals(100L, result[0].timestamp)
    }

    @Test
    fun `filters synthetic messages`() {
        every { eventDispatcher.messages.value } returns mapOf(
            "session1" to listOf(userMessage("u1", 100), userMessage("u2", 200))
        )
        every { eventDispatcher.parts.value } returns mapOf(
            "u1" to listOf(textPart("u1", "Real message", synthetic = false)),
            "u2" to listOf(textPart("u2", "System injected", synthetic = true))
        )
        val result = manager.findLatestUserMessages("session1", 5)
        assertEquals(1, result.size)
        assertEquals("Real message", result[0].text)
    }

    @Test
    fun `skips user messages with no text parts`() {
        every { eventDispatcher.messages.value } returns mapOf(
            "session1" to listOf(userMessage("u1", 100))
        )
        every { eventDispatcher.parts.value } returns mapOf(
            "u1" to emptyList()
        )
        val result = manager.findLatestUserMessages("session1", 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `truncates long text to 100 chars`() {
        val longText = "x".repeat(200)
        every { eventDispatcher.messages.value } returns mapOf(
            "session1" to listOf(userMessage("u1", 100))
        )
        every { eventDispatcher.parts.value } returns mapOf(
            "u1" to listOf(textPart("u1", longText))
        )
        val result = manager.findLatestUserMessages("session1", 5)
        assertEquals(101, result[0].text.length) // 100 chars + "…"
        assertTrue(result[0].text.endsWith("…"))
    }

    @Test
    fun `returns at most limit messages, most recent last`() {
        val msgs = (1..10).map { userMessage("u$it", it.toLong()) }
        every { eventDispatcher.messages.value } returns mapOf("session1" to msgs)
        every { eventDispatcher.parts.value } returns (1..10).associate {
            "u$it" to listOf(textPart("u$it", "Message $it"))
        }
        val result = manager.findLatestUserMessages("session1", 3)
        assertEquals(3, result.size)
        assertEquals("Message 8", result[0].text)
        assertEquals("Message 10", result[2].text)
    }
}
