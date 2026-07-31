package dev.leonardo.ocbeacon.builder

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.JsonElement

/** Generate a random ID for test data. */
fun randomId(): String = java.util.UUID.randomUUID().toString()

private var idCounter = 0L
private fun nextPartId(): String = "part-${idCounter++}"

/**
 * DSL builder for constructing List<Part> with sensible defaults.
 * Each method creates a Part with auto-incrementing IDs and matching sessionId/messageId.
 */
class PartListBuilder(
    private val sessionId: String = "test-session",
    private val messageId: String = "msg-1"
) {
    private val parts = mutableListOf<Part>()

    fun text(content: String) {
        parts.add(Part.Text(
            id = nextPartId(),
            sessionId = sessionId,
            messageId = messageId,
            text = content
        ))
    }

    fun reasoning(content: String) {
        parts.add(Part.Reasoning(
            id = nextPartId(),
            sessionId = sessionId,
            messageId = messageId,
            text = content
        ))
    }

    fun tool(
        name: String,
        state: ToolState = ToolState.Running(output = "", title = name),
        callId: String = nextPartId()
    ) {
        parts.add(Part.Tool(
            id = nextPartId(),
            sessionId = sessionId,
            messageId = messageId,
            callId = callId,
            tool = name,
            state = state
        ))
    }

    fun toolCompleted(name: String, output: String) {
        parts.add(Part.Tool(
            id = nextPartId(),
            sessionId = sessionId,
            messageId = messageId,
            callId = nextPartId(),
            tool = name,
            state = ToolState.Completed(
                output = output,
                title = name,
                time = ToolState.Completed.Time(
                    start = System.currentTimeMillis() - 1000,
                    end = System.currentTimeMillis()
                )
            )
        ))
    }

    fun permission(question: String) {
        parts.add(Part.Permission(
            id = nextPartId(),
            sessionId = sessionId,
            messageId = messageId,
            message = question
        ))
    }

    fun question(text: String, options: List<String>) {
        parts.add(Part.Question(
            id = nextPartId(),
            sessionId = sessionId,
            messageId = messageId,
            question = text
        ))
    }

    fun patch(oldText: String, newText: String) {
        parts.add(Part.Patch(
            id = nextPartId(),
            sessionId = sessionId,
            messageId = messageId,
            hash = "${oldText.hashCode()}-${newText.hashCode()}",
            files = listOf("test-file.txt")
        ))
    }

    fun file(name: String, content: String) {
        parts.add(Part.File(
            id = nextPartId(),
            sessionId = sessionId,
            messageId = messageId,
            mime = "text/plain",
            filename = name,
            url = "data:text/plain;base64,${java.util.Base64.getEncoder().encodeToString(content.toByteArray())}"
        ))
    }

    fun stepStart() {
        parts.add(Part.StepStart(
            id = nextPartId(),
            sessionId = sessionId,
            messageId = messageId
        ))
    }

    fun stepFinish() {
        parts.add(Part.StepFinish(
            id = nextPartId(),
            sessionId = sessionId,
            messageId = messageId
        ))
    }

    fun abort() {
        parts.add(Part.Abort(
            id = nextPartId(),
            sessionId = sessionId,
            messageId = messageId
        ))
    }

    fun build(): List<Part> = parts.toList()
}

/**
 * Create a user Message for tests.
 */
fun aUserMessage(
    text: String,
    id: String = randomId(),
    sessionId: String = "test-session"
): Message.User = Message.User(
    id = id,
    sessionId = sessionId,
    time = TimeInfo(created = System.currentTimeMillis())
)

/**
 * Create an assistant Message with parts.
 * Returns MessageWithParts so the caller gets both the message and its parts.
 */
fun anAssistantMessage(
    streaming: Boolean = false,
    id: String = randomId(),
    error: String? = null,
    sessionId: String = "test-session",
    block: PartListBuilder.() -> Unit = {}
): MessageWithParts {
    val builder = PartListBuilder(sessionId = sessionId, messageId = id)
    builder.block()
    val parts = builder.build()

    val message = Message.Assistant(
        id = id,
        sessionId = sessionId,
        parentId = "parent-${id}",
        time = TimeInfo(
            created = System.currentTimeMillis(),
            completed = if (streaming) null else System.currentTimeMillis()
        ),
        error = error?.let {
            Message.Assistant.ErrorInfo(name = "TestError", data = null)
        }
    )

    return MessageWithParts(info = message, parts = parts)
}
