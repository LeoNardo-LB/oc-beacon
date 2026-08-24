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
            "session.next.moved" -> SessionNextEvent.Moved.serializer()
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
            "session.next.usage.updated" -> SessionNextEvent.UsageUpdated.serializer()
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

    /**
     * 2026-08-15（research/11 P1）：会话跨目录移动（V2 session.next.moved，
     * payload {location, subdirectory}）——官方 TUI 增量更新 directory
     *（sync.tsx:300-314）。此前未处理 → 会话移动后本地分组错位。
     */
    @Serializable
    data class Moved(
        @SerialName("sessionID") override val sessionId: String,
        val location: String = "",
        val subdirectory: String? = null
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
        val content: List<ToolOutputContent> = emptyList(),
        /**
         * 2026-08-15（research/08 P0，对齐官方 V2 契约）：progress 事件的
         * 结构化输出——官方语义是**整体替换**（非拼接）：
         * - `session.tool.progress`（当前部署版，实测抓帧）：metadata.output
         *   为服务端 preview(last+chunk) 全量尾部快照（core shell.ts:220）
         * - `session.next.tool.progress`（主干 schema）：structured+content
         * 保留原始 JSON 由消费侧按需提取。
         */
        val structured: kotlinx.serialization.json.JsonObject? = null,
        /** 实测（2026-08-15 抓帧）当前部署版的输出在 metadata 字段。 */
        val metadata: kotlinx.serialization.json.JsonObject? = null
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
        val step: Int,
        /** 结束原因：stop（正常停止，turn 结束）/ tool-calls（调用工具，turn 继续）/ length / content-filter 等。 */
        val finish: String = "",
        /** 服务器事件时间戳（epoch ms）——用服务器时刻记录 turn 结束，避免客户端处理延迟
         * 造成的"退出后 step.ended 才到达 → 红点误报"（2026-08-07）。 */
        @SerialName("timestamp") val timestamp: Long = 0,
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
        @SerialName("messageID") val messageId: String,
        /** #219（2026-08-25）：非空 = 压缩失败（session.compaction.failed 的 error.message）。 */
        val error: String = ""
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

    /**
     * 2026-08-15：session 级 token 用量更新（V2 `session.usage.updated`，
     * 实测 payload：{sessionID, cost, tokens:{input,output,reasoning,cache:{read,write}}}）。
     * 服务器权威的累计用量——顶部 context 指示器的正解数据源（消息级 tokens
     * 会因 REST 覆盖/冷启动缺失而归零，session 级始终有效）。
     */
    @Serializable
    data class UsageUpdated(
        @SerialName("sessionID") override val sessionId: String,
        val cost: Double = 0.0,
        val tokens: SessionUsageTokens = SessionUsageTokens()
    ) : SessionNextEvent()

    @Serializable
    data class SessionUsageTokens(
        val input: Int = 0,
        val output: Int = 0,
        val reasoning: Int = 0,
        val cache: SessionUsageCache = SessionUsageCache()
    ) {
        /**
         * 上下文占用量。2026-08-15 修正：**不含 cache.read**——session 级
         * tokens 的 cache.read 是历史累计（跨所有请求求和，实测可达数百万），
         * 不是当前上下文构成；加入会超 100%（实测 447%）。上下文占用 =
         * input + output + reasoning（活跃部分，与 opencode TUI 语义一致）。
         * 消息级 tokens（step.ended）的 cache.read 是单次请求快照，语义不同
         * （消息级统计口径不变，见 ChatViewModel collect）。
         */
        val contextTotal: Int get() = input + output + reasoning
    }

    @Serializable
    data class SessionUsageCache(
        val read: Int = 0,
        val write: Int = 0
    )

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
