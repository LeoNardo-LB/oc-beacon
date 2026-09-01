package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.PermissionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.QuestionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * FSM 必需协作者——[SessionStateService] 的全部外部事实单一接线点（#174）。
 *
 * 原 8 个可缺省 var 回调（漏接即静默降级：directoryResolver 默认 null → REST 打错路由）
 * 收拢为本 interface：**全抽象、无默认**，构造期注入——漏接从静默降级变为编译错误。
 *
 * 语义分工（每个方法 = FSM 的一个外部问题）：
 * - 消息域：[hasIncompleteAssistant]（流式保护）/ [refreshMessages]（L3+断连补漏回写，
 *   策略语义见各调用点）/ [latestMessageId]（增量游标锚点）/ [forceCompleteSession]（终态兜底）
 * - 路由域：[resolveDirectory]（REST 按目录路由）
 * - 合法性域：[hasPendingUserInput] / [hasActiveChildren]（僵尸判定防护）
 * - 副作用域：[onNaturalTurnEnd]（堆积消息推进）
 */
interface SessionStateCollaborator {
    fun hasIncompleteAssistant(sessionId: String): Boolean
    fun resolveDirectory(sessionId: String): String?
    fun forceCompleteSession(sessionId: String)
    fun refreshMessages(sessionId: String, messages: List<MessageWithParts>, strategy: MergeStrategy)
    fun latestMessageId(sessionId: String): String?
    fun hasPendingUserInput(sessionId: String): Boolean
    fun hasActiveChildren(serverId: String, sessionId: String): Boolean
    fun onNaturalTurnEnd(sessionId: String, serverId: String?)
}

/**
 * 生产接线（原 EventDispatcher.init 75 行接线块整体迁入，逻辑零变更）。
 *
 * 依赖无环：messages/sessions/questions/permissions/unread 均为无状态依赖的单例；
 */
@Singleton
class SessionStateCollaboratorImpl @Inject constructor(
    private val messageHandler: MessageEventHandler,
    private val sessionHandler: SessionEventHandler,
    private val questionHandler: QuestionEventHandler,
    private val permissionHandler: PermissionEventHandler,
    private val unreadBadgeService: UnreadBadgeService,
    private val sessionRepoProvider: Provider<SessionRepository>,
) : SessionStateCollaborator {

    override fun hasIncompleteAssistant(sessionId: String): Boolean =
        messageHandler.messages.value[sessionId].orEmpty()
            .filterIsInstance<dev.leonardo.ocbeacon.domain.model.Message.Assistant>()
            .any { it.time.completed == null }

    override fun resolveDirectory(sessionId: String): String? =
        // 2026-08-16（状态误杀修复）：directory 空串归一化为 null——空串非 null
        // 会以空 directory header 查询 /active，服务器路由结果未定义 → 活跃
        // 会话查不到 → 「缺失即 idle」误杀。
        sessionHandler.sessions.value.find { it.id == sessionId }?.directory?.ifBlank { null }

    override fun forceCompleteSession(sessionId: String) {
        // markSessionIdle 用客户端 now 标记 UI 流式终止，但不写入红点时间源
        //（红点判定只用服务器时刻，#171 起经 UnreadEvent 类型承载）。
        messageHandler.markSessionIdle(sessionId)
        // 落盘兜底：idle 到达时，前序 MessageUpdated(completed) 已更新内存红点时间源，
        // 此刻触发落盘确保杀进程不丢（seed 恢复兜底，有界丢失窗口：毫秒级）。
        unreadBadgeService.persistAsync()
    }

    override fun refreshMessages(sessionId: String, messages: List<MessageWithParts>, strategy: MergeStrategy) {
        // 2026-08-16 根治：透传合并策略——SSE 断连窗口补漏用 SSE_PRIORITY
        //（不覆盖 SSE 累积流式文本），L3 校验保持 REST_AUTHORITY。
        messageHandler.upsertMessages(sessionId, messages, strategy)
    }

    override fun latestMessageId(sessionId: String): String? =
        // #55：L3 校验增量补漏的游标锚点（V2 NEWER 方向增量拉取）
        messageHandler.messages.value[sessionId]?.maxByOrNull { it.time.created }?.id

    override fun hasPendingUserInput(sessionId: String): Boolean =
        // 2026-08-14 走查修复（僵尸误杀防护）：等待用户回答的 question/permission
        // 存在时服务器合法运行中，僵尸判定不得 interrupt。
        questionHandler.questions.value[sessionId]?.isNotEmpty() == true ||
            permissionHandler.permissions.value[sessionId]?.isNotEmpty() == true

    override fun hasActiveChildren(serverId: String, sessionId: String): Boolean =
        // 2026-08-15（僵尸误杀修复·二）：活跃子智能体会话（parentID 指向它且服务器 running）
        // 存在时不得 interrupt——V2 drain 语义下等待后台任务的主会话自身无事件流。
        // 用会话缓存 parentID + 服务器 /active 对照（复用 fetchSessionStatuses 免双倍请求）。
        kotlinx.coroutines.runBlocking {
            val children = sessionHandler.sessions.value
                .filter { it.parentId == sessionId }
            if (children.isEmpty()) return@runBlocking false
            val directory = children.firstNotNullOfOrNull { it.directory.ifBlank { null } }
            val statuses = sessionRepoProvider.get()
                .fetchSessionStatuses(serverId, directory).getOrNull() ?: return@runBlocking false
            children.any { statuses[it.id] is SessionStatus.Busy }
        }

    override fun onNaturalTurnEnd(sessionId: String, serverId: String?) {
        // 堆积消息管线（2026-08-20 设计定稿）：自然成功 turn 结束 → 推进队列。
        // Pipeline 经 Provider 注入（其内部 SendMessageUseCase → ChatRepositoryImpl
        // → EventDispatcher 循环由 Provider 延迟解析打破）。
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionStateCollaboratorModule {
    @Binds
    abstract fun bindSessionStateCollaborator(impl: SessionStateCollaboratorImpl): SessionStateCollaborator
}
