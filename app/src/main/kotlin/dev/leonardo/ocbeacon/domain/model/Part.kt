package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Part 的自定义序列化器，根据 "type" 字段分发。
 */
object PartSerializer : JsonContentPolymorphicSerializer<Part>(Part::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Part> {
        val obj = element.jsonObject
        return when (obj["type"]?.jsonPrimitive?.content) {
            "text" -> Part.Text.serializer()
            "reasoning" -> Part.Reasoning.serializer()
            "tool" -> Part.Tool.serializer()
            "shell" -> Part.Shell.serializer()
            "step-start" -> Part.StepStart.serializer()
            "step-finish" -> Part.StepFinish.serializer()
            "file" -> Part.File.serializer()
            "snapshot" -> Part.Snapshot.serializer()
            "patch" -> Part.Patch.serializer()
            "subtask" -> Part.Subtask.serializer()
            "compaction" -> Part.Compaction.serializer()
            "retry" -> Part.Retry.serializer()
            "abort" -> Part.Abort.serializer()
            "agent" -> Part.Agent.serializer()
            // #200 F01：补齐与 typeName() 对称的分发（原缺分支落 Unknown）
            "permission" -> Part.Permission.serializer()
            "question" -> Part.Question.serializer()
            "session-turn" -> Part.SessionTurn.serializer()
            // 2026-08-12 修复：旧数据/SSE 播种的 parts 无 "type" 字段
            //（Part.Text(text="") 序列化省略默认值 → payload 无 type 无 text）
            // ——按顶层字段推断，避免降级为 Unknown（Unknown 导致消息流
            // 误导显示 "Running command…"——用户反馈"缺少数据/对话快速访问问题"）
            else -> when {
                // #300②：缓存 payload 恒无 type（序列化按具体类进行，子类无 type
                // 属性），Reasoning 的内容字段名就是 "text"——若 containsKey("text")
                // 先命中，Reasoning 恒误判为 Text（下方 "reasoning" 键分支是任何
                // Part 都不会序列化出的死分支）。派生 id 契约（PartIdContract，
                // kind 编入 id）先于字段推断。
                obj["id"]?.jsonPrimitive?.contentOrNull
                    ?.contains(PartIdContract.REASONING_MARKER) == true -> Part.Reasoning.serializer()
                obj.containsKey("text") -> Part.Text.serializer()
                obj.containsKey("reasoning") -> Part.Reasoning.serializer()
                obj.containsKey("tool") || obj.containsKey("state") -> Part.Tool.serializer()
                obj.containsKey("shell") -> Part.Shell.serializer()
                obj.containsKey("subtask") -> Part.Subtask.serializer()
                obj.containsKey("patch") -> Part.Patch.serializer()
                // #200 F01：缓存回环推断补 Permission/Question（原落 Unknown；
                // "message"/"question" 为两类独有顶层字段，无他类冲突）
                obj.containsKey("message") -> Part.Permission.serializer()
                obj.containsKey("question") -> Part.Question.serializer()
                else -> Part.Unknown.serializer()
            }
        }
    }
}

/**
 * Message Part —— 消息中不同类型的内容。
 * 字段名使用 @SerialName 以匹配 OpenCode API 约定（大写 ID 后缀）。
 */
@Serializable(with = PartSerializer::class)
sealed class Part {
    abstract val id: String
    abstract val sessionId: String
    abstract val messageId: String

    @Serializable
    data class Text(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val text: String = "",
        val synthetic: Boolean? = null,
        val ignored: Boolean? = null,
        val time: Time? = null,
        val metadata: Map<String, JsonElement>? = null
    ) : Part() {
        @Serializable
        data class Time(val start: Long, val end: Long? = null)
    }

    @Serializable
    data class Reasoning(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val text: String = "",
        val time: Time? = null,
        val metadata: Map<String, JsonElement>? = null
    ) : Part() {
        @Serializable
        data class Time(val start: Long, val end: Long? = null)
    }

    @Serializable
    data class Tool(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String = "",
        @SerialName("messageID") override val messageId: String = "",
        @SerialName("callID") val callId: String = "",
        val tool: String = "",
        val state: ToolState,
        val metadata: Map<String, JsonElement>? = null
    ) : Part()

    /**
     * 后台 shell 命令 part（V2 消息流中的 Shell 卡片数据）。
     *
     * V2 服务器在 `session.shell.started` 时向会话注入该 part：
     * `{id, type:"shell", shellID, command, status, metadata, time:{created}}`，
     * `session.shell.ended` 时更新 status/exit/output。
     */
    @Serializable
    data class Shell(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String = "",
        @SerialName("messageID") override val messageId: String = "",
        /** V2 beta 形态 = shellID；上游 dev 漂移形态 = callID（#256 双形态容错）。 */
        @SerialName("shellID") val shellId: String = "",
        val command: String = "",
        val status: String = "",
        val exit: Int? = null,
        val output: String? = null,
        /** 捕获截断标记（beta-18414 实测恒 false，1MiB 上限；true 时输出需经 REST 续读）。 */
        val truncated: Boolean = false,
        val time: Time? = null,
        val metadata: Map<String, JsonElement>? = null
    ) : Part() {
        @Serializable
        data class Time(val start: Long, val end: Long? = null)
    }

    @Serializable
    data class StepStart(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val snapshot: String? = null
    ) : Part()

    @Serializable
    data class StepFinish(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val reason: String = "",
        val snapshot: String? = null,
        val cost: Double? = null,
        val tokens: Tokens? = null
    ) : Part() {
        @Serializable
        data class Tokens(
            val input: Int = 0,
            val output: Int = 0,
            val total: Int? = null,
            val reasoning: Int = 0,
            val cache: Cache? = null
        )

        @Serializable
        data class Cache(
            val read: Int = 0,
            val write: Int = 0
        )
    }

    @Serializable
    data class File(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val mime: String,
        val filename: String? = null,
        val url: String? = null,
        val source: JsonElement? = null
    ) : Part()

    @Serializable
    data class Snapshot(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val snapshot: String = ""
    ) : Part()

    @Serializable
    data class Patch(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val hash: String = "",
        val files: List<String> = emptyList()
    ) : Part()

    @Serializable
    data class Subtask(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val prompt: String = "",
        val description: String? = null,
        val agent: String? = null,
        val model: Model? = null,
        val command: String? = null
    ) : Part() {
        @Serializable
        data class Model(
            @SerialName("providerID") val providerId: String,
            @SerialName("modelID") val modelId: String
        )
    }

    @Serializable
    data class Compaction(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val auto: Boolean = false,
        /** 2026-08-15：压缩后的全文（V2 REST compaction 消息的 text）——
         *  分割线卡片可展开查看（此前仅标记无内容）。 */
        val summary: String? = null,
        /** #219（2026-08-25）：status=failed 的压缩消息——渲染为失败分割线
         *  （「压缩会话失败」标签 + 错误色），而非误导性的成功「已压缩」。 */
        val failed: Boolean = false
    ) : Part()

    @Serializable
    data class Retry(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val attempt: Int = 0,
        val error: JsonElement? = null,
        val time: Time? = null
    ) : Part() {
        @Serializable
        data class Time(val created: Long)

        val errorMessage: String
            get() = error?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Unknown error"    }

    @Serializable
    data class Agent(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val name: String = "",
        val source: JsonElement? = null
    ) : Part()

    @Serializable
    data class Permission(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val message: String = ""
    ) : Part()

    @Serializable
    data class Question(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val question: String = ""
    ) : Part()

    @Serializable
    data class Abort(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val reason: String = ""
    ) : Part()

    @Serializable
    data class SessionTurn(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String
    ) : Part()

    @Serializable
    data class Unknown(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String
    ) : Part()
}
