package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SessionNextEvent 的判别式序列化器。
 * 使用 "type" 字段选择正确的变体。
 * 对于未识别的类型，回退到 [SessionNextEvent.Unknown]。
 */
object SessionNextEventSerializer : JsonContentPolymorphicSerializer<SessionNextEvent>(SessionNextEvent::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SessionNextEvent> {
        val type = element.jsonObject["type"]?.jsonPrimitive?.content ?: return SessionNextEvent.Unknown.serializer()
        return when (type) {
            "session.next.agent.switched" -> SessionNextEvent.AgentSwitched.serializer()
            "session.next.model.switched" -> SessionNextEvent.ModelSwitched.serializer()
            "session.next.text.started" -> SessionNextEvent.TextStarted.serializer()
            "session.next.text.delta" -> SessionNextEvent.TextDelta.serializer()
            "session.next.text.ended" -> SessionNextEvent.TextEnded.serializer()
            "session.next.reasoning.started" -> SessionNextEvent.ReasoningStarted.serializer()
            "session.next.reasoning.delta" -> SessionNextEvent.ReasoningDelta.serializer()
            "session.next.reasoning.ended" -> SessionNextEvent.ReasoningEnded.serializer()
            "session.next.tool.input.started" -> SessionNextEvent.ToolInputStarted.serializer()
            "session.next.tool.input.delta" -> SessionNextEvent.ToolInputDelta.serializer()
            "session.next.tool.called" -> SessionNextEvent.ToolCalled.serializer()
            "session.next.tool.progress" -> SessionNextEvent.ToolProgress.serializer()
            "session.next.tool.success" -> SessionNextEvent.ToolSuccess.serializer()
            "session.next.tool.failed" -> SessionNextEvent.ToolFailed.serializer()
            "session.next.step.started" -> SessionNextEvent.StepStarted.serializer()
            "session.next.step.ended" -> SessionNextEvent.StepEnded.serializer()
            "session.next.step.failed" -> SessionNextEvent.StepFailed.serializer()
            "session.next.shell.started" -> SessionNextEvent.ShellStarted.serializer()
            "session.next.shell.ended" -> SessionNextEvent.ShellEnded.serializer()
            "session.next.compaction.started" -> SessionNextEvent.CompactionStarted.serializer()
            "session.next.compaction.delta" -> SessionNextEvent.CompactionDelta.serializer()
            "session.next.compaction.ended" -> SessionNextEvent.CompactionEnded.serializer()
            "session.next.prompted" -> SessionNextEvent.Prompted.serializer()
            "session.next.retried" -> SessionNextEvent.Retried.serializer()
            "session.next.synthetic" -> SessionNextEvent.Synthetic.serializer()
            else -> SessionNextEvent.Unknown.serializer()
        }
    }
}

/**
 * 用于实时状态跟踪的细粒度会话事件类型。
 * 事件采用 `session.next.{category}.{action}` 命名约定。
 * 当 SSE 流中 type 以 "session.next." 开头时解析。
 */
@Serializable(with = SessionNextEventSerializer::class)
sealed class SessionNextEvent {
    abstract val sessionId: String

    // ============ Agent / Model 切换 ============

    /** 此会话切换了 Agent。 */
    @Serializable
    data class AgentSwitched(
        @SerialName("sessionID") override val sessionId: String,
        val agent: String
    ) : SessionNextEvent()

    /** 此会话切换了 Model。 */
    @Serializable
    data class ModelSwitched(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("providerID") val providerId: String,
        @SerialName("modelID") val modelId: String
    ) : SessionNextEvent()

    // ============ 文本流 ============

    @Serializable
    data class TextStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String
    ) : SessionNextEvent()

    @Serializable
    data class TextDelta(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        val delta: String
    ) : SessionNextEvent()

    @Serializable
    data class TextEnded(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String
    ) : SessionNextEvent()

    // ============ 推理流 ============

    @Serializable
    data class ReasoningStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String
    ) : SessionNextEvent()

    @Serializable
    data class ReasoningDelta(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        val delta: String
    ) : SessionNextEvent()

    @Serializable
    data class ReasoningEnded(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String
    ) : SessionNextEvent()

    // ============ 工具执行 ============

    @Serializable
    data class ToolInputStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val tool: String
    ) : SessionNextEvent()

    @Serializable
    data class ToolInputDelta(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val delta: String
    ) : SessionNextEvent()

    @Serializable
    data class ToolCalled(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val tool: String,
        val input: Map<String, JsonElement> = emptyMap()
    ) : SessionNextEvent()

    /** OpenCode ToolOutput.Content 元素 —— 工具输出内容块。 */
    @Serializable
    data class ToolOutputContent(
        val type: String = "text",
        val text: String = ""
    )

    @Serializable
    data class ToolProgress(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val progress: String? = null,
        val title: String? = null,
        val content: List<ToolOutputContent> = emptyList()
    ) : SessionNextEvent()

    @Serializable
    data class ToolSuccess(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val output: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class ToolFailed(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        @SerialName("callID") val callId: String,
        val error: String = ""
    ) : SessionNextEvent()

    // ============ Step 生命周期 ============

    @Serializable
    data class StepStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        val step: Int,
        val agent: String = "",
        val model: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class StepEnded(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        val step: Int
    ) : SessionNextEvent()

    @Serializable
    data class StepFailed(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        val step: Int,
        val error: String = ""
    ) : SessionNextEvent()

    // ============ Shell ============


    @Serializable
    data class ShellStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        val command: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class ShellEnded(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String,
        val exitCode: Int = 0
    ) : SessionNextEvent()

    // ============ 压缩 ============

    @Serializable
    data class CompactionStarted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        val reason: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class CompactionDelta(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String,
        val delta: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class CompactionEnded(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String
    ) : SessionNextEvent()

    // ============ 其他 ============

    @Serializable
    data class Prompted(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String
    ) : SessionNextEvent()

    @Serializable
    data class Retried(
        @SerialName("sessionID") override val sessionId: String,
        val attempt: Int = 0,
        val error: String = ""
    ) : SessionNextEvent()

    @Serializable
    data class Synthetic(
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") val messageId: String
    ) : SessionNextEvent()

    /** 未识别的 session.next.* 事件类型的回退。 */
    @Serializable
    data class Unknown(
        val rawType: String = "",
        val rawJson: String = ""
    ) : SessionNextEvent() {
        @SerialName("sessionID")
        override val sessionId: String = ""
    }
}
