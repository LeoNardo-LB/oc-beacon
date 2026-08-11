package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.runtime.Immutable
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.CommandInfo
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent

/**
 * 拆分状态：消息列表与分页数据。
 * 每次新增消息/part 更新时变化 —— 频率最高。
 */
@Immutable
data class MessageListState(
    val messages: List<ChatMessage> = emptyList(),
    val messageCount: Int = 0,
    val hasOlderMessages: Boolean = false,
    val isLoadingOlder: Boolean = false,
    /** 自动续载暂停（连续失败达上限）——UI 停止自动分页，等待手动触发。 */
    val autoLoadPaused: Boolean = false,
    val toolExpandedStates: Map<String, Boolean> = emptyMap(),
    val queuedMessageIds: Set<String> = emptySet(),
    /**
     * 原始（未过滤）消息 —— 本会话 combine 管道内的消息快照（revert/加载空态过滤前）。
     * 供 fixIncompleteMessagesIfIdle 检查（避免新 assistant 消息尚无 parts 时的窗口期），
     * 以及 messagesList 投影（TokenStatsTracker / markSessionRead）。
     * #44：sseJob 双订阅合并后由 combine 统一提供，消除独立观察管道。
     */
    val rawMessages: List<Message> = emptyList(),
    /**
     * 全部消息的 parts 映射（key=messageId，跨会话无冲突）。
     * 供 messagesList 投影的"assistant 无 parts 过滤"判断，避免重复观察 parts 源。
     */
    val partsByMessageId: Map<String, List<Part>> = emptyMap(),
)

/**
 * 拆分状态：会话元数据。
 * 会话信息更新时变化（标题、状态、agent）。
 */
@Immutable
data class SessionMetaState(
    val sessionTitle: String = "",
    val serverName: String = "",
    val sessionStatus: SessionStatus = SessionStatus.Idle,
    val revert: Session.Revert? = null,
    val sessionParentId: String? = null,
    val sessionAgent: String? = null,
    val currentAgentName: String? = null,
    val currentModelId: String? = null,
    val shareUrl: String? = null,
    /** 当本会话的 FSM activity 为 Streaming 时为 true（控制 reasoning 计时器 + streamingMsgId）。 */
    val isStreaming: Boolean = false,
)

/**
 * 拆分状态：用户交互状态。
 * 在加载/发送/出错及待处理权限/问题时变化。
 */
@Immutable
data class InteractionState(
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val error: String? = null,
    val pendingPermissions: List<SseEvent.PermissionAsked> = emptyList(),
    val pendingQuestions: List<SseEvent.QuestionAsked> = emptyList(),
)

/**
 * 拆分状态：token 使用统计。
 * 每次流式 token 更新时变化 —— 生成期间频率高。
 */
@Immutable
data class TokenStatsState(
    val totalCost: Double = 0.0,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val totalReasoningTokens: Int = 0,
    val totalCacheReadTokens: Int = 0,
    val totalCacheWriteTokens: Int = 0,
    val contextWindow: Int = 0,
    val lastContextTokens: Int = 0,
)

/**
 * 拆分状态：模型/agent 配置与已解析的选择项。
 * 在 provider 加载、用户选择或消息历史更新（用于自动解析）时变化。
 * 包含从消息历史解析模型/agent 的副作用逻辑。
 */
@Immutable
data class ModelConfigState(
    val providers: List<ProviderCatalog> = emptyList(),
    val hasServerModelCatalog: Boolean = false,
    val defaultModels: Map<String, String> = emptyMap(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgent: String = "build",
    val variantNames: List<String> = emptyList(),
    val selectedVariant: String? = null,
    val commands: List<CommandInfo> = emptyList(),
    /** 上下文窗口大小 —— 从 token 统计解析，带 provider 回退。 */
    val contextWindow: Int = 0,
)

data class ChatUiState(
    val sessionTitle: String = "",
    val serverName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val messageCount: Int = 0,
    val revert: Session.Revert? = null,
    val sessionStatus: SessionStatus = SessionStatus.Idle,
    val pendingPermissions: List<SseEvent.PermissionAsked> = emptyList(),
    val pendingQuestions: List<SseEvent.QuestionAsked> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val providers: List<ProviderCatalog> = emptyList(),
    val hasServerModelCatalog: Boolean = false,
    val defaultModels: Map<String, String> = emptyMap(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val totalCost: Double = 0.0,
    /** 会话总量，从所有已加载的 assistant 消息计算（非 session.tokens，后者可能是单次调用的值）。 */
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val totalReasoningTokens: Int = 0,
    val totalCacheReadTokens: Int = 0,
    val totalCacheWriteTokens: Int = 0,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgent: String = "build",
    val variantNames: List<String> = emptyList(),
    val selectedVariant: String? = null,
    val commands: List<CommandInfo> = emptyList(),
    /** 服务器上存在尚未加载的更早消息时为 true。 */
    val hasOlderMessages: Boolean = false,
    /** "加载更早消息" 请求进行中时为 true。 */
    val isLoadingOlder: Boolean = false,
    /** 会话已分享时的分享 URL，否则为 null。 */
    val shareUrl: String? = null,
    /** 当前模型的上下文窗口大小（未知时为 0）。 */
    val contextWindow: Int = 0,
    /** 最后一条 output > 0 的 assistant 消息的 token 总量（当前上下文使用量）。 */
    val lastContextTokens: Int = 0,
    /** 已排队（在 assistant 仍在生成时发送）的用户消息 ID 集合。 */
    val queuedMessageIds: Set<String> = emptySet(),
    /** 父会话 ID —— 当本会话是子会话/sub-agent 会话时非空。 */
    val sessionParentId: String? = null,
    /** 本会话的 agent 名称（如 "explore"、"general"）。子 agent 会话时填充。 */
    val sessionAgent: String? = null,
    /** 已持久化的工具卡片展开/折叠状态，以 Part.Tool.id 或 Part.Patch.id 为键。 */
    val toolExpandedStates: Map<String, Boolean> = emptyMap(),
    val currentAgentName: String? = null,
    val currentModelId: String? = null,
    /** 发送失败后恢复的草稿。仅在消费前非空一次。 */
    val restoredDraft: RevertedDraftPayload? = null,
)

data class RevertedDraftPayload(
    val text: String,
    val attachmentUris: List<String> = emptyList(),
)

/**
 * UI 用的扁平化聊天消息。
 * 将 Message 信息与其 parts 组合在一起。
 */
data class ChatMessage(
    val message: Message,
    val parts: List<Part>
) {
    val isUser: Boolean get() = message is Message.User
    val isAssistant: Boolean get() = message is Message.Assistant
}
