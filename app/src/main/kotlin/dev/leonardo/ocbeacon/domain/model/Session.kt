package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Session —— 表示一个 OpenCode 对话会话。
 * 字段名匹配 OpenCode API 约定（大写 ID 后缀）。
 */
@Serializable
data class Session(
    val id: String,
    val slug: String = "",
    @SerialName("projectID") val projectId: String = "",
    val directory: String = "",
    @SerialName("parentID") val parentId: String? = null,
    val title: String? = null,
    val version: String = "",
    val time: Time,
    val summary: Summary? = null,
    val share: Share? = null,
    val permission: List<PermissionRule>? = null,
    val revert: Revert? = null,
    // --- V2 新增字段 ---
    @SerialName("workspaceID") val workspaceId: String? = null,
    val path: String? = null,
    val cost: Double? = null,
    val tokens: SessionTokens? = null,
    val agent: String? = null,
    val model: SessionModel? = null,
    /** DSH 权限预设状态（permissions 投影 / 三 knob 事件折叠）；OpenCode V1/V2 无此域 → null。 */
    val permissions: SessionPermissions? = null,
    /** DSH 空白会话标记（事件流无 turn/start）；驱动空白页预设卡门控。OpenCode 恒 false。 */
    val blank: Boolean = false,
    /** DSH 当前 Agent 预设 id（agentPreset 字段 / agent-preset/selected 事件）；OpenCode 恒 null。 */
    val agentPreset: String? = null,
) {
    @Serializable
    data class Time(
        val created: Long,
        val updated: Long,
        val compacting: Long? = null,
        val archived: Long? = null
    )

    @Serializable
    data class Summary(
        val additions: Int = 0,
        val deletions: Int = 0,
        val files: Int = 0,
        val diffs: List<FileDiff>? = null
    )

    @Serializable
    data class Share(val url: String)

    @Serializable
    data class Revert(
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String? = null,
        val snapshot: String? = null,
        val diff: String? = null
    )

    @Serializable
    data class PermissionRule(
        val permission: String,
        val pattern: String = "*",
        val action: String = "ask"
    )

    @Serializable
    data class SessionTokens(
        val input: Int = 0,
        val output: Int = 0,
        val reasoning: Int = 0,
        val cache: Cache = Cache()
    ) {
        @Serializable
        data class Cache(val read: Int = 0, val write: Int = 0)
    }

    @Serializable
    data class SessionModel(
        val id: String,
        @SerialName("providerID") val providerId: String,
        val variant: String? = null
    )

    val createdAt: Long
        get() = time.created

    val isArchived: Boolean
        get() = time.archived != null
}

/**
 * 会话及其当前状态与最后一条消息。
 */
data class SessionWithStatus(
    val session: Session,
    val status: SessionStatus,
    val lastMessageData: MessageWithParts? = null
) {
    val lastMessage: MessageWithParts?
        get() = lastMessageData
}
