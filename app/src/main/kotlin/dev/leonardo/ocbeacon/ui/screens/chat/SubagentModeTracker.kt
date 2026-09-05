package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

private const val TAG = "SubagentModeTracker"

/**
 * #310① 子会话续聊 mode 追踪器（纯流逻辑，单测 SubagentModeTrackerTest）。
 *
 * 三源输入（当前会话 id / 会话列表（parentId 来源）/ 服务器类型）→ 当前会话的
 * subagents 目录 mode（continuable|one-shot）：
 * - 主会话（无 parentId）或非 DSH → 恒 null，零 RPC；
 * - DSH 子会话 → 先发 null（加载中保守隐藏 composer——防 one-shot 误发），再
 *   懒加载 subagentCatalog(parentId) 取 entries 中本会话 child 行的 mode；
 * - 失败 / 目录无本行 / 非 DSH 端点缺席（null 目录）→ 停留 null + AppLogger.w。
 *
 * sid+parentId+serverType 三元组 distinctUntilChanged——会话列表字段刷新
 * （标题变更等）不重触发 RPC。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SubagentModeTracker(
    private val chatRepository: ChatRepository,
    private val serverId: String,
) {
    fun modeFlow(
        sessionIdFlow: Flow<String>,
        sessionsFlow: Flow<List<Session>>,
        serverTypeFlow: Flow<ServerType>,
    ): Flow<String?> = combine(
        sessionIdFlow,
        sessionsFlow,
        serverTypeFlow,
    ) { sid, sessions, type ->
        Triple(sid, sessions.firstOrNull { it.id == sid }?.parentId, type)
    }.distinctUntilChanged()
        .flatMapLatest { (sid, parentId, type) ->
            if (parentId == null || type != ServerType.Dsh) {
                flowOf(null)
            } else {
                flow<String?> {
                    // 加载中保守 null（composer 隐藏）
                    emit(null)
                    chatRepository.subagentCatalog(serverId, parentId)
                        .onSuccess { catalog ->
                            emit(
                                catalog?.entries
                                    ?.firstOrNull { entry -> entry.isChild && entry.id == sid }
                                    ?.mode,
                            )
                        }
                        .onFailure { e ->
                            AppLogger.w(TAG, "subagentCatalog load failed for $sid: " + e.message)
                        }
                }
            }
        }
        // mode 值去重：加载 null → 解析 null（目录无本行）只保留一个 null
        .distinctUntilChanged()
}
