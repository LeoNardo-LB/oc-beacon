package dev.leonardo.ocbeacon.util

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MessageFingerprintsTest {

    private fun textPart(id: String, text: String): Part = Part.Text(
        id = id, sessionId = "s1", messageId = "m1", text = text
    )

    private fun userMessage(id: String, text: String): ChatMessage = ChatMessage(
        message = Message.User(id = id, sessionId = "s1", time = TimeInfo(created = 100L)),
        parts = listOf(textPart("p$id", text))
    )

    private fun assistantMessage(id: String, text: String): ChatMessage = ChatMessage(
        message = Message.Assistant(
            id = id, sessionId = "s1", time = TimeInfo(created = 100L, completed = 200L), parentId = "p"
        ),
        parts = listOf(textPart("p$id", text))
    )

    @Test
    fun `messagesSignature same input same signature`() {
        val a = listOf(userMessage("1", "hi"), assistantMessage("2", "hello"))
        val b = listOf(userMessage("1", "hi"), assistantMessage("2", "hello"))
        assertEquals(MessageFingerprints.messagesSignature(a), MessageFingerprints.messagesSignature(b))
    }

    @Test
    fun `messagesSignature different ids different signature`() {
        val a = listOf(userMessage("1", "hi"))
        val b = listOf(userMessage("2", "hi"))
        assertNotEquals(MessageFingerprints.messagesSignature(a), MessageFingerprints.messagesSignature(b))
    }

    @Test
    fun `messageFingerprint same content same fingerprint`() {
        val a = assistantMessage("1", "same text")
        val b = assistantMessage("1", "same text")
        assertEquals(MessageFingerprints.messageFingerprint(a), MessageFingerprints.messageFingerprint(b))
    }

    @Test
    fun `messageFingerprint different text different fingerprint`() {
        val a = assistantMessage("1", "alpha")
        val b = assistantMessage("1", "beta")
        assertNotEquals(MessageFingerprints.messageFingerprint(a), MessageFingerprints.messageFingerprint(b))
    }

    @Test
    fun `messagesSignature empty list boundary`() {
        assertEquals(
            MessageFingerprints.messagesSignature(emptyList()),
            MessageFingerprints.messagesSignature(emptyList())
        )
    }

    @Test
    fun `tailHash long text differs from short hash`() {
        val short = MessageFingerprints.tailHash("x")
        val long = MessageFingerprints.tailHash("x".repeat(100))
        assertNotEquals(short, long)
    }

    @Test
    fun `toolFingerprint running output affects fingerprint`() {
        fun tool(output: String) = Part.Tool(
            id = "t1", sessionId = "s1", messageId = "m1", callId = "c1", tool = "bash",
            state = ToolState.Running(output = output)
        )
        assertNotEquals(
            MessageFingerprints.partsFingerprint(listOf(tool("out-a"))),
            MessageFingerprints.partsFingerprint(listOf(tool("out-b")))
        )
    }
}
